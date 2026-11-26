# OpenRAC Server Coding Conventions

Every rule below is countable. If you cannot count your compliance, you have not complied.
Re-read this file before each change; do not work from a remembered summary of it.

## Hard Rules

These seven are non-negotiable. Count each one against your own diff before you report the work
as done. See [Self-Check Before Submitting](#self-check-before-submitting).

### 1. No `var` declarations

❌ `var party = session.getParty();`

✅ `Party party = session.getParty();`

Applies to every declaration: locals, loop variables and method results.

### 2. No braces around single-line statements

❌
```java
if (party == null) {
    return;
}
```

✅
```java
if (party == null) return;
```

You may break onto a new line if the statement is very long. The same goes for `for` and `else`.

### 3. Comments cap at 12 words

Count the words. Aim for 6 to 10. Twelve is the ceiling, not the target.

If a thought needs more than 12 words, it is not a line comment. Put it in the method's JavaDoc,
or use a `/* */` block. Do not chain clauses with a semicolon or a comma to fit a second sentence in.

### 4. Every branch says why it is there

**Comment each conditional.** An `if`, an `else if`, a `case`, a guard clause, a ternary that
decides something: each is a rule somebody chose, and the reason is not on the line. Say it.

The same goes for any line whose reason is not visible in it: a cast that matters, an ordering
that cannot be swapped, a limit that exists because of one caller.

Straight sequential statements do not each need one.

```java
// A session belongs to one party at a time.
if (session.getParty() != null) {
    session.send(new ErrorPacket(Opcodes.Error.ALREADY_IN_PARTY));
    return;
}

// Each game only links so many players.
if (party.isFull()) {
    session.send(new ErrorPacket(Opcodes.Error.PARTY_FULL));
    return;
}
```

**The comment says why, never what.** `// Check if the party is full.` above `if (party.isFull())`
is the line typed twice, and it is deleted. `// Each game only links so many players.` says
something the code cannot.

If a branch has no reason worth writing, notice it: either the reason is obvious from a name a few
lines up, and the branch stays bare, or the branch should not be there. A plain dispatch `switch`
whose cases only call the handler of the same name is the usual bare case.

### 5. Never rewrite an existing comment you did not break

If a comment is there and still accurate, leave it exactly as it is. You may only touch an
existing comment when the code beneath it changed and made it wrong.

### 6. No em dashes, anywhere

Not in comments, JavaDoc, log strings, docs or commit messages. Use a comma, a period, a colon or
parentheses. A plain hyphen between numbers (`1-5`) is fine.

### 7. Comments sound like a person

Never write these in a comment:
- Consequence essays: `// which is the safe way round for a leave to fail`
- Reassurance: `// so the client can simply retry`
- Tour-guide transitions: `Furthermore`, `Moreover`, `Additionally`
- Hedges: `It is worth noting that`, `It should be noted that`
- Vague hype: `robust`, `comprehensive`, `seamless`, `powerful`

State what the line is for. Stop there.

---

## Whitespace and Spacing

- **4 spaces** for indentation, never tabs.
- **K&R braces**: the opening brace sits on the line of the statement.
- **One blank line** after a class JavaDoc, after a method JavaDoc, between methods, between field
  groups, after a guard clause and between logical sections of a method.
- **Single spaces** around binary operators, none after unary ones. A space between a keyword and
  its parenthesis (`if (`), none between a method name and its parenthesis (`send(`).
- **Line length**: about 160 characters. Wrap long calls and conditions at logical breakpoints.
  A constant or field declaration stays on one line.

```java
/**
 * Sends a packet to every member.
 * @param packet The packet to send.
 */

public void broadcast(OutgoingPacket packet) {
    for (Session member : members) member.send(packet);
}
```

---

## Comments

### Line comments (`//`)

- Always on the line **before** the code, never at the end of a code line.
- 12 words at most, a complete sentence: a capital letter, a full stop, one idea.
- One comment covers a group of related statements. Do not comment each line of a group.

**The deletion test**: read the comment, then the line. If the comment is the line restated in
English, delete the comment.

### End-of-line comments

Only for fields and constants, never for code logic.

```java
private Session session; // Null until the client says hello.
public static final int MAX_PARTY_SIZE = 4; // Host included.
```

### Block comments (`/* */`)

Rare: one explanation that will not fit on a line, four lines at most. Never a method summary
(that is JavaDoc) and never a banner. Three stacked `//` lines are a block comment written wrong.

---

## JavaDoc

- **Every class** has a JavaDoc saying what it is for, followed by one blank line.
- **Every public and protected method** has a JavaDoc: one to three sentences, an `@param` per
  parameter and an `@return` for non-void methods. Private helpers get one too.
- Simple getters may use the short form:

```java
/**
 * @return The join code.
 */

public String getCode() {
    return code;
}
```

- Empty hooks say what they are for. Convenience overloads may have shorter docs.

---

## Imports

Groups in this order, a blank line between them:

1. External libraries (`io.netty.*`, `org.junit.*`)
2. Project (`com.openrac.*`)
3. Java standard library (`java.*`, `javax.*`)

Constants are read through their class name (`Constants.MAX_PARTY_SIZE`), never through
`import static`.

---

## Class Structure

Elements within a class go in this order:

1. Static constants
2. Static fields
3. Instance fields, grouped by category
4. Constructors
5. Overrides (behaviour overrides; getter-shaped overrides go with the getters)
6. Load methods, all together
7. Helpers
8. Getters (`get*`, `is*`, `has*`, `can*`), all together
9. Setters (`set*`), all together, right after the getters
10. `serialize()`, always the last method
11. Nested records, after `serialize()`

Methods are ordered by role, not by visibility.

---

## Fields and Constructors

- Initialize fields at their declaration where possible, and make them `final` when they never change.
- Initialize collections at declaration with the type that fits the threading:
  `new CopyOnWriteArrayList<>()` for a list read from many threads and changed rarely.
- Chain overloaded constructors with `this(...)`.

---

## Methods and Control Flow

- A method body runs guard clauses first, then preparation, then the main logic, then the return.
- Guard clauses return early; each is followed by a blank line.
- `switch` uses the arrow form.
- Ternaries are for simple choices. A ternary that decides something gets a comment like any branch.

---

## Naming

| Pattern | Usage | Examples |
|---|---|---|
| `*Controller` | Static classes that run a system-wide operation | `PartyController` |
| `*Handler` | Instance-based processors, such as Netty handlers | `GamePacketHandler` |
| `*Packet` | Packets, built in their constructors | `HelloPacket`, `PartyPacket` |
| `*Constants` | Values a module decides itself | `Constants`, `NetworkConstants` |
| `*Tests` | Test classes | `PacketCodecTests` |

| Prefix | Usage |
|---|---|
| `handle*` | Packet handlers and callbacks |
| `get*` / `set*` | Accessors |
| `is*` / `has*` / `can*` | Boolean accessors |
| `serialize` | Writing an object for the wire |

- Variables are camelCase and descriptive.
- `UPPER_SNAKE_CASE` is only for compile-time constants. A `static final` created at runtime
  (an `AtomicInteger`, a `SecureRandom`, a map) is camelCase.

---

## Network and Serialization

### Packets

A packet extends `OutgoingPacket` and writes its payload in its constructor. The constructor names
the packet id and, where the packet has one, its opcode, both from the enums in `:common`.

```java
public ErrorPacket(Opcodes.Error reason) {
    super(Packets.ERROR.ordinal(), reason.ordinal());
}
```

Objects sent over the wire implement `serialize()`, returning an `OutputBuffer` a packet appends.

### Never hardcode a wire value

A packet id, an opcode, a reason, or anything else whose number two processes agree on is declared
**once**, in `:common`, as an enum. Never as an `int` constant in the class that happens to use it.
Switch on the enum, not the number, so the compiler tracks which cases exist.

**The ordinal is the wire byte, so an enum that crosses the wire is append-only.** Reordering or
inserting a value renumbers everything after it and breaks every client that has not shipped the
same change.

A `*Constants` class holds what one side decides alone: a cap, a default port. A value that would
have to change on both sides at once is a wire value and belongs in `:common`.

---

## Logging

```java
Log.info("Listening on port " + port + ".");
Log.warning(address + " sent an invalid hello, closing.");
Log.error("Error on " + address + ", closing: " + cause.getMessage());
```

Messages carry their context (session id, name, address) and end with a full stop.

---

## Testing

- JUnit with `@Nested` classes and `@DisplayName` on every class and test.
- Test classes end in `Tests`; test methods start with `test`.
- Assertions go through `Assertions.assertEquals(...)`, without static imports.
- Network code is tested on the real pipeline, through Netty's `EmbeddedChannel`.

```java
@Nested
@DisplayName("Error Tests")
class ErrorTests {

    @Test
    @DisplayName("Every error reason survives the round trip")
    public void testErrors() {
        // ...
    }
}
```

---

## Reuse

- **Search before you write.** Before adding a helper, a constant or a check, grep for it. If it
  exists, call it.
- **The rule of two.** The second time you write a piece of logic, extract it.
- **Put logic where the data lives.** Forwarding rules belong on `Party`, not in the handler that
  reaches through its getters.
- **Use what is already resolved.** Do not re-derive a value computed earlier in the same method.

---

## Self-Check Before Submitting

Count each one. Do not report work as complete until every line reads yes.

**Comments**
1. Is every `//` comment 12 words or fewer?
2. Does every conditional carry a comment saying why it is there, bare dispatch aside?
3. Did you delete every comment that restates the line beneath it?
4. Are existing comments you did not break unchanged?
5. Zero em dashes in the diff?
6. No `//` stack three lines tall?

**Structure**
7. Are methods ordered overrides, loads, helpers, getters, setters, `serialize()`, records?
8. Does every class and every public or protected method have JavaDoc?

**Reuse**
9. Does any logic in the diff appear twice?
10. Is every wire value an enum or constant in `:common`?

**Syntax**
11. Zero `var` declarations?
12. Zero single-line statements wrapped in braces?
