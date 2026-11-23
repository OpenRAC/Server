#!/usr/bin/env python3
"""Checks the countable rules of docs/CODING_CONVENTIONS.md.

Java sources get every rule below. Every other tracked text file is checked for em dashes only,
and so are the messages of the commits in --commits.

    var            no `var` declarations (rule 1)
    braces         no braces around a single-line if, else, for or while body (rule 2)
    comment-words  a `//` comment holds at most 12 words (rule 3)
    em-dash        no em dashes anywhere (rule 6)
    comment-stack  no `//` stack three lines tall
    eol-comment    end-of-line comments only on fields and constants
    javadoc        JavaDoc on every class and every public or protected method
    import-static  no `import static`

Usage:
    scripts/check_conventions.py --all                    every tracked file
    scripts/check_conventions.py --base origin/master     files changed since the merge base
    scripts/check_conventions.py FILE...                  the files named
    add --commits BASE..HEAD to check those commit messages for em dashes too
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

EM_DASH = chr(0x2014)
MAX_COMMENT_WORDS = 12
MAX_STACK = 2

ROOT = Path(__file__).resolve().parent.parent

# Text files worth reading for em dashes; binaries and the wrapper jar are skipped.
TEXT_SUFFIXES = {".java", ".kts", ".kt", ".md", ".sql", ".json", ".toml", ".properties", ".yml", ".yaml", ".sh", ".py", ".txt", ".bat"}
TEXT_NAMES = {"Dockerfile", ".gitignore", ".dockerignore", ".env.defaults", "gradlew", "LICENSE"}

MODIFIERS = r"(?:public|protected|private|static|final|abstract|sealed|non-sealed|default|synchronized|native|strictfp|transient|volatile)"
TYPE_DECLARATION = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:" + MODIFIERS + r"\s+)*(class|interface|enum|record|@interface)\s+(\w+)")
METHOD_DECLARATION = re.compile(r"^\s*(?:" + MODIFIERS + r"\s+)*(?:<[^>]+>\s+)?[\w<>\[\],.? ]+?\s+(\w+)\s*\([^;]*$|^\s*(?:" + MODIFIERS + r"\s+)*(\w+)\s*\(")
VAR_DECLARATION = re.compile(r"(?:^\s*|[(;]\s*|\bfinal\s+)var\s+\w+\s*[=:,)]")
CONTROL_OPEN = re.compile(r"^\s*(?:\}\s*)?(?:if|else\s+if|for|while)\s*\(.*\)\s*\{\s*$|^\s*(?:\}\s*)?else\s*\{\s*$")
FIELD_LINE = re.compile(r"^\s*(?:@\w+\s+)*(?:" + MODIFIERS + r"\s+)*[\w<>\[\],.? ]+\s+\w+\s*(?:=.*)?;\s*$")
ANONYMOUS_CLASS = re.compile(r"\bnew\s+[\w.<>]+\s*\(.*\)\s*\{\s*$")
ENUM_CONSTANT = re.compile(r"^\s*[A-Z][A-Z0-9_]*\s*(?:\(.*\))?\s*(?:\{\s*)?[,;]?\s*$")
STATEMENT_KEYWORDS = ("return", "throw", "if", "for", "while", "switch", "try", "do", "new", "else", "case", "yield", "break", "continue", "assert")


class Finding:
    """One broken rule, at a file and line."""

    def __init__(self, path, line, rule, message):
        self.path = path
        self.line = line
        self.rule = rule
        self.message = message

    def __str__(self):
        return f"{self.path}:{self.line}: [{self.rule}] {self.message}"


class Line:
    """A source line split into its code, with strings blanked, and its `//` comment."""

    def __init__(self, number, text, code, comment, in_block):
        self.number = number
        self.text = text
        self.code = code
        self.comment = comment
        self.in_block = in_block


def split_lines(source):
    """Splits Java source into lines, keeping string contents and comments out of the code."""
    lines = []
    in_block = False
    in_text_block = False

    for number, text in enumerate(source.split("\n"), start=1):
        code = []
        comment = None
        started_in_block = in_block or in_text_block
        i = 0

        while i < len(text):
            # Inside a block comment, everything up to its end is comment.
            if in_block:
                end = text.find("*/", i)

                if end < 0:
                    i = len(text)
                    break

                in_block = False
                i = end + 2
                continue

            # A text block runs until its closing quotes.
            if in_text_block:
                end = text.find('"""', i)

                if end < 0:
                    i = len(text)
                    break

                in_text_block = False
                code.append('""')
                i = end + 3
                continue

            if text.startswith("//", i):
                comment = text[i + 2:]
                break

            if text.startswith("/*", i):
                in_block = True
                i += 2
                continue

            if text.startswith('"""', i):
                in_text_block = True
                i += 3
                continue

            char = text[i]

            # Strings and characters keep their quotes but lose their contents.
            if char in "\"'":
                j = i + 1

                while j < len(text) and text[j] != char:
                    j += 2 if text[j] == "\\" else 1

                code.append(char + char)
                i = j + 1
                continue

            code.append(char)
            i += 1

        lines.append(Line(number, text, "".join(code), comment, started_in_block))

    return lines


def count_words(comment):
    """Words in a comment; a lone symbol such as `&` is no word."""
    return sum(1 for token in comment.split() if re.search(r"\w", token))


def comment_stacks(lines):
    """Yields each run of consecutive whole-line `//` comments as a list of lines."""
    stack = []

    for line in lines:
        # A whole-line comment has no code before it.
        if line.comment is not None and not line.code.strip():
            stack.append(line)
            continue

        if stack: yield stack
        stack = []

    if stack: yield stack


def type_body_lines(lines):
    """The line numbers whose start sits directly in a class, interface, enum or record body."""
    members = set()
    bodies = []
    depth = 0
    opens_body = False

    for line in lines:
        code = line.code

        if bodies and bodies[-1] == depth: members.add(line.number)

        # A declaration's next brace opens a body, wherever that brace is.
        if TYPE_DECLARATION.match(code) or ANONYMOUS_CLASS.search(code): opens_body = True

        for char in code:
            if char == "{":
                depth += 1

                if opens_body:
                    bodies.append(depth)
                    opens_body = False
            elif char == "}":
                # Leaving a body ends it.
                if bodies and bodies[-1] == depth: bodies.pop()

                depth -= 1

    return members


def check_comments(path, lines, findings):
    """Comment length, stack height and end-of-line placement."""
    members = type_body_lines(lines)

    for stack in comment_stacks(lines):
        words = sum(count_words(line.comment) for line in stack)

        if len(stack) > MAX_STACK:
            findings.append(Finding(path, stack[0].number, "comment-stack", f"{len(stack)} stacked // lines; use JavaDoc or a /* */ block"))
        elif words > MAX_COMMENT_WORDS:
            findings.append(Finding(path, stack[0].number, "comment-words", f"{words} words in a // comment, the cap is {MAX_COMMENT_WORDS}"))

    for line in lines:
        code = line.code.strip()

        # Whole-line comments were counted above.
        if line.comment is None or not code: continue

        if count_words(line.comment) > MAX_COMMENT_WORDS:
            findings.append(Finding(path, line.number, "comment-words", f"{count_words(line.comment)} words in an end-of-line comment"))

        # Fields, constants and enum constants may carry one.
        if line.number in members and (FIELD_LINE.match(code) or ENUM_CONSTANT.match(code)): continue

        findings.append(Finding(path, line.number, "eol-comment", "end-of-line comment on a code line; move it above"))


def check_syntax(path, lines, findings):
    """`var`, `import static` and single statements in braces."""
    for line in lines:
        if VAR_DECLARATION.search(line.code):
            findings.append(Finding(path, line.number, "var", "a var declaration; name the type"))

        if re.match(r"^\s*import\s+static\b", line.code):
            findings.append(Finding(path, line.number, "import-static", "import static; read constants through their class"))

    code_lines = [line for line in lines if line.code.strip()]

    for index, line in enumerate(code_lines[:-2]):
        # Only a brace that opens an if, else, for or while body.
        if not CONTROL_OPEN.match(line.code): continue

        body = code_lines[index + 1]
        closing = code_lines[index + 2]

        # The body must be one complete statement, and the next line the closing brace.
        if closing.code.strip() not in ("}", "} else {") and not re.match(r"^\s*\}\s*else\b", closing.code): continue

        statement = body.code.strip()

        if not statement.endswith(";") or statement.startswith("}") or "{" in statement: continue

        # A comment inside the braces is a second line of the body.
        if any(other.comment is not None and not other.code.strip() for other in lines[line.number:closing.number - 1]): continue

        findings.append(Finding(path, line.number, "braces", "braces around a single-line statement"))


def preceded_by_javadoc(lines, index):
    """Whether the declaration at lines[index] has a JavaDoc above its annotations."""
    i = index - 1

    while i >= 0:
        text = lines[i].text.strip()

        # Blank lines and annotations sit between a JavaDoc and its declaration.
        if not text or (text.startswith("@") and not lines[i].in_block):
            i -= 1
            continue

        if not text.endswith("*/"): return False

        # Walk back to the comment's opening and check it is a JavaDoc.
        while i >= 0 and "/*" not in lines[i].text:
            i -= 1

        return i >= 0 and "/**" in lines[i].text

    return False


def annotations_above(lines, index):
    """The annotations directly above a declaration."""
    found = []
    i = index - 1

    while i >= 0:
        text = lines[i].text.strip()

        if text.startswith("@"):
            found.append(text)
        elif text:
            break

        i -= 1

    return found


def check_javadoc(path, lines, findings):
    """Every class, interface, enum and record, and every public or protected method."""
    depth = 0
    interface_depths = []

    for index, line in enumerate(lines):
        code = line.code
        stripped = code.strip()
        type_match = TYPE_DECLARATION.match(code)

        if type_match and not stripped.startswith("new "):
            annotations = annotations_above(lines, index)

            # Nested test classes carry a @DisplayName instead.
            if not any(a.startswith("@Nested") for a in annotations) and not preceded_by_javadoc(lines, index):
                findings.append(Finding(path, line.number, "javadoc", f"{type_match.group(1)} {type_match.group(2)} has no JavaDoc"))

            if type_match.group(1) in ("interface", "@interface"): interface_depths.append(depth + 1)
        elif stripped and not stripped.startswith(("return", "new ", "else", "throw")):
            is_public = re.match(r"^\s*(?:" + MODIFIERS + r"\s+)*(?:public|protected)\b", code) is not None
            in_interface = bool(interface_depths) and interface_depths[-1] == depth
            method = METHOD_DECLARATION.match(code)
            declares = method and "=" not in code.split("(")[0] and not re.match(r"^\s*(?:if|for|while|switch|catch|synchronized|try)\b", code)

            # Interface methods are public without saying so.
            if declares and (is_public or (in_interface and not re.match(r"^\s*(?:private|static)\b", code))) and depth > 0:
                name = method.group(1) or method.group(2)
                annotations = annotations_above(lines, index)

                # Test methods carry a @DisplayName instead.
                if not any(a.startswith(("@Test", "@ParameterizedTest")) for a in annotations) and not preceded_by_javadoc(lines, index):
                    findings.append(Finding(path, line.number, "javadoc", f"method {name} has no JavaDoc"))

        depth += code.count("{") - code.count("}")

        # Leaving an interface body ends its implicit publics.
        while interface_depths and depth < interface_depths[-1]:
            interface_depths.pop()


def check_em_dashes(path, text, findings):
    """No em dashes in any file, nor their escapes in Java."""
    for number, line in enumerate(text.split("\n"), start=1):
        if EM_DASH in line or (path.endswith(".java") and "\\u2014" in line.lower()):
            findings.append(Finding(path, number, "em-dash", "an em dash; use a comma, colon, period or parentheses"))


def check_file(path):
    """Every rule that applies to one file."""
    findings = []

    try:
        text = (ROOT / path).read_text(encoding="utf-8")
    except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError):
        return findings

    check_em_dashes(path, text, findings)

    if path.endswith(".java"):
        lines = split_lines(text)

        check_comments(path, lines, findings)
        check_syntax(path, lines, findings)
        check_javadoc(path, lines, findings)

    return findings


def check_commits(commit_range):
    """No em dashes in the messages of the commits in a range."""
    findings = []
    log = git("log", "--format=%H%x00%B%x01", commit_range)

    for entry in log.split("\x01"):
        # The log ends with an empty entry after the last separator.
        if "\x00" not in entry: continue

        sha, message = entry.strip("\n").split("\x00", 1)

        if EM_DASH in message:
            findings.append(Finding(f"commit {sha[:12]}", 1, "em-dash", "an em dash in the commit message"))

    return findings


def git(*args):
    """Runs git in the repository and returns its output."""
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def is_text(path):
    """Whether a tracked path is a text file worth reading."""
    name = Path(path).name

    return Path(path).suffix in TEXT_SUFFIXES or name in TEXT_NAMES


def main():
    """Picks the files, runs the checks and prints what broke."""
    parser = argparse.ArgumentParser(description="Checks the countable rules of docs/CODING_CONVENTIONS.md.")
    parser.add_argument("files", nargs="*", help="files to check, relative to the repository root")
    parser.add_argument("--all", action="store_true", help="check every tracked file")
    parser.add_argument("--base", help="check the files changed since the merge base with this ref")
    parser.add_argument("--commits", help="also check the messages of the commits in this range (BASE..HEAD)")
    args = parser.parse_args()

    if args.all:
        files = git("ls-files").split("\n")
    elif args.base:
        files = git("diff", "--name-only", "--diff-filter=ACMR", f"{args.base}...HEAD").split("\n")
    else:
        files = args.files

    files = sorted(path for path in files if path and is_text(path))
    findings = []

    for path in files:
        findings.extend(check_file(path))

    if args.commits:
        findings.extend(check_commits(args.commits))

    for finding in findings:
        print(finding)

    print(f"{len(files)} files checked, {len(findings)} findings.", file=sys.stderr)

    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
