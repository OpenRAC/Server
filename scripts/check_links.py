#!/usr/bin/env python3
"""Checks the relative links and anchors in README.md and docs/*.md.

A link to another file must name one that exists, and a #anchor must match a heading in the file
it points into, slugged the way GitHub slugs headings. Web links are left alone.

Usage:
    scripts/check_links.py              README.md and every docs/*.md
    scripts/check_links.py FILE...      the files named
"""

import re
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent.parent

LINK = re.compile(r"(?<!!)\[(?:[^\]\\]|\\.)*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
IMAGE = re.compile(r"!\[[^\]]*\]\(\s*<?([^)\s>]+)>?\s*\)")
REFERENCE = re.compile(r"^\s*\[[^\]]+\]:\s*<?(\S+?)>?(?:\s+.*)?$")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
EXPLICIT_ANCHOR = re.compile(r"<a\s+(?:name|id)=\"([^\"]+)\"")


def strip_code(text):
    """Blanks fenced blocks and inline code, keeping the line count."""
    lines = []
    fenced = False

    for line in text.split("\n"):
        # A fence opens or closes a block, which holds no links.
        if line.lstrip().startswith(("```", "~~~")):
            fenced = not fenced
            lines.append("")
            continue

        lines.append("" if fenced else re.sub(r"`[^`]*`", "", line))

    return lines


def slug(heading):
    """GitHub's anchor for a heading: lower case, punctuation gone, spaces as hyphens."""
    text = re.sub(r"`([^`]*)`", r"\1", heading)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"<[^>]+>", "", text).strip().lower()
    text = re.sub(r"[^\w\- ]", "", text)

    return text.replace(" ", "-")


def anchors(path, cache={}):
    """Every anchor a Markdown file has, repeats numbered as GitHub numbers them."""
    if path in cache: return cache[path]

    found = set()
    seen = {}

    for line in strip_code(path.read_text(encoding="utf-8")):
        found.update(EXPLICIT_ANCHOR.findall(line))
        match = HEADING.match(line)

        if not match: continue

        base = slug(match.group(2))
        count = seen.get(base, 0)

        # GitHub numbers a repeated heading's anchor from 1.
        found.add(base if count == 0 else f"{base}-{count}")
        seen[base] = count + 1

    cache[path] = found

    return found


def check(path):
    """The broken links of one Markdown file, as messages."""
    problems = []
    relative = path.relative_to(ROOT)

    for number, line in enumerate(strip_code(path.read_text(encoding="utf-8")), start=1):
        targets = LINK.findall(line) + IMAGE.findall(line)
        reference = REFERENCE.match(line)

        if reference: targets.append(reference.group(1))

        for target in targets:
            # Web and mail links are not this check's business.
            if re.match(r"^[a-z][a-z0-9+.-]*:", target, re.IGNORECASE): continue

            file_part, _, anchor = target.partition("#")
            destination = path if not file_part else (path.parent / unquote(file_part)).resolve()

            if not destination.exists():
                problems.append(f"{relative}:{number}: {target}: no such file")
                continue

            # Only Markdown files have headings to point into.
            if anchor and destination.suffix == ".md" and unquote(anchor).lower() not in anchors(destination):
                problems.append(f"{relative}:{number}: {target}: no heading with the anchor #{anchor}")

    return problems


def main():
    """Checks the files and prints every broken link."""
    paths = [ROOT / name for name in sys.argv[1:]] or [ROOT / "README.md", *sorted((ROOT / "docs").glob("*.md"))]
    problems = []

    for path in paths:
        problems.extend(check(path.resolve()))

    for problem in problems:
        print(problem)

    print(f"{len(paths)} files checked, {len(problems)} broken links.", file=sys.stderr)

    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
