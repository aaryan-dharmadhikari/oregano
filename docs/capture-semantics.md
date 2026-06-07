# Capture-group semantics: where Oregano matches `java.util.regex`, and the one wart it doesn't

Oregano's oracle for capture groups is `java.util.regex` (`Matcher.start(i)/end(i)/group(i)`).
Across the supported subset (literals, classes, `|`, `*`, `+`, `(…)`, `(?:…)`, `.`) Oregano
reproduces Java's capture offsets exactly — with **one** deliberate exception, documented at
the bottom. This note records both the parts we match and the part we don't, with the
reasoning, because the difference is subtle and easy to "fix" in the wrong direction.

## Slot layout

A capture result is `Option[Array[Int]]` laid out as
`[start_0, end_0, start_1, end_1, …]`, group 0 = the whole match, `-1` for an unmatched
group. Length `= 2 * (groupCount + 1)`. This maps exactly onto Java's
`m.start(i)/m.end(i)` over `0 to m.groupCount`, so the two are directly comparable with no
hand-computed expectations (see `StagedProgCaptureDifferentialTests`).

Group numbering is by **opening-paren order, left to right** (Java's rule). NB this depends
on `PatternBuilder.compile` walking a concatenation's children left-to-right: `nextGroup` is
assigned as a side effect, so a right-to-left fold (e.g. `foldRight` forcing its cons cells
from the right) silently reverses sibling group ids. `Pattern.scala` uses `rs.map(compile)`
for exactly this reason.

## What we match: last-iteration-wins, *including the final empty iteration*

For a group inside a repetition, Java keeps the **last** iteration's captures (write-before-
match, restore-on-failure). The subtle part is the *final empty iteration*: after the last
progressing iteration of a nullable body, Java/PCRE run the body **once more**, it matches
empty, and Java **keeps** that empty iteration's group writes.

```
(a*)*b   on "aab"   →  java g1 = [2,2]   (inner (a*) matched EMPTY at the end)
(a*)*b   on "b"     →  java g1 = [0,0]   (one empty iteration ran)
```

Oregano reproduces this. The mechanism: in the recursive loop, when the body matches empty
(`next == p`) we must **not** return `-1` (that would unwind the empty iteration's captures in
the `CAPTURE` handler) and must **not** recurse (infinite loop). Instead we run the loop
*exit* continuation at that position, so the one empty iteration's group writes survive:

```scala
// BacktrackingProgMatcher, recursive LOOP path
def exit(p: Int): Int = …continuation after the loop…
def loop(p: Int): Int = {
  val step = …body, with continuation:
      (next) => if (next != p) loop(next)   // advanced: keep iterating
                else exit(next)             // empty: exit here, KEEPING this iteration's captures
  …
  if (step >= 0) step else exit(p)
}
```

`CPSMatcher.Rep0` carries the identical change (`else ${cont(next)}` / `else cont(nextPos, …)`),
so Oregano's two independent engines agree with each other *and* with Java here. This is
boolean-match-identical to the old code (which returned `-1` then fell through to `exit(p)`
anyway) — only the capture values change, toward Java.

## What we do NOT match: Java's capture-leak-on-backtrack wart

This is the exception. When a **quantifier** (`*`/`+`) backtracks past an iteration that
captured, Java does **not** roll back the nested group's value — it leaks the value from the
iteration that was ultimately abandoned.

```
((a))+(a)      on "aa"   →  java g2 = [1,2]   Oregano g2 = [0,1]
(([c])[b])+(cb) on "cbcb" →  java g2 = [2,3]   Oregano g2 = [0,1]
(([ac]))*c     on "c"    →  java g2 = "c"      Oregano g2 = <unmatched>
```

Take `((a))+(a)` on `"aa"`. The `+` greedily matches two iterations (`g1=g2=[1,2]`), then the
trailing `(a)` fails at end-of-input, so the second iteration is abandoned and the `+` settles
on one iteration. Java restores the **loop's own** group (`g1 → [0,1]`) but leaves the nested
`g2` at `[1,2]` — a span that is actually consumed by `g3`, *outside* the loop's winning
`[0,1]`. Oregano (and RE2, Rust's `regex`, Go's `regexp`) roll back **all** nested captures on
backtrack and report the consistent `g2 = [0,1]`.

This leak is specific to **quantifier** backtracking. Failed **alternation** branches roll
back captures in Java just like Oregano:

```
((a)b|a)c   on "ac"  →  java g2 = null   (1st branch captured 'a' then failed; rolled back)
```

### Why we don't replicate it

- It is a well-known Java/PCRE **wart**, not a spec. RE2, Rust and Go deliberately reject it;
  Oregano's behaviour is the same as theirs and is the more defensible "the winning match's
  groups" semantics.
- Replicating it is invasive and high-risk: it means **dropping capture rollback on
  backtrack** in the `CAPTURE` handler of both engines. That rollback is load-bearing
  elsewhere (it's why failed alternation branches don't leak), so a bug-for-bug rewrite would
  be a large change in service of copying a behaviour most modern engines consider a defect.

### How the tests encode this boundary

- **Targeted offset tests** (`StagedProgCaptureDifferentialTests`, and the hardcoded tables in
  the CPS/Prog matcher tests) pin exact Java offsets on fixed, wart-free patterns — including
  the empty-iteration cases above.
- **The capture property test** (`RegexPropertyTests`) fuzzes random regexes against Java and
  compares captured **text** (`m.group(i)`), not raw offsets — the meaningful, user-facing
  invariant. Text comparison still catches genuine divergences (empty-iteration differs as
  `"" vs "a"`) while tolerating pure-offset coincidences.
- To keep that property *true* rather than flaky, its generator (`genCaptureSafeRegex`) places
  capturing groups everywhere **except inside a quantified subexpression** — the sole trigger
  of the leak. Boolean-match fuzzing keeps the full generator, since the leak never affects
  whether a string matches.
