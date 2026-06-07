# The Backoffs optimisation: completeness boundary and a no-better-than-CPS argument

This note characterises *exactly* what the `Backoffs` fast-path in the staged
Prog backtracking engine (`BacktrackingProgMatcher`) can do, and argues *why*
nothing structural does better than CPS/recursion outside that boundary. Together
they justify the engine's hybrid design: Backoffs for fixed-width unambiguous loop
bodies, the recursive continuation path for everything else
(`bodyNeedsRecursivePath` is precisely this boundary).

## Background

A greedy loop `B*` is matched by `Backoffs` as follows: a single greedy `forward`
pass matches the body `B` repeatedly, recording the run as `(width, count)` frames
(a `parent`-linked list); `backtrack` then tries the loop's continuation at
positions obtained by *dropping trailing iterations*:

```
exit at   finalPos − i·width   for i = 0,1,…  (then descend to the parent frame)
```

Crucially Backoffs stores the **widths** produced by one greedy traversal, never
the **choices** that produced them, and it **never re-matches** `B` — backtracking
is pure position arithmetic.

## Theorem 1 (completeness boundary)

> Backoffs is complete — it finds a match iff one exists, with correct greedy
> precedence — **iff the loop body `B` is fixed-width and unambiguous**: for every
> position `p`, `B` matches at `p` in at most one way, consuming a context-
> independent number of characters `w`.

**Reachable set.** With one greedy run from `start`, the only positions at which
`backtrack` ever tries the continuation are the prefixes of that run:

```
Reachable = { p₀ = start, p₁, p₂, …, pₙ = finalPos },   p_{k+1} = p_k + width_k
```

**Sufficiency.** If `B` is fixed-width `w` and unambiguous, then after `k`
iterations the loop is *necessarily* at `start + k·w`, so the set of legitimate
loop-end positions is *exactly* `{start, start+w, start+2w, …}` = `Reachable`,
visited longest-first. Nothing is missed. ∎

**Necessity.** If `B` can match at some position two ways with widths `w₁ < w₂`
(an alternation of unequal-length branches), or contains a nested loop that can
give repetitions back, then the valid loop-end positions lie on **multiple
chains**, one per sequence of choices. Greedy `forward` commits to one choice at
each position and records a single chain `C`; `backtrack` only visits `C`; a match
that lives on another chain `C′ ≠ C` is unreachable. ∎

**Witnesses.**
- `(a|ab)*c` on `"abc"`: greedy takes `a` ⟹ `C = {0,1}`; the match needs the
  continuation `c` at position **2** (chain `{0,2}`), and `2 ∉ C`.
- `(b*)*bc` on `"bbc"`: greedy eats `bb` (width 2) ⟹ `C = {2,0}`; the match needs
  position **1** (the inner loop giving one `b` back), and `1 ∉ C`.

## Theorem 2 (no structural mechanism beats CPS off the boundary)

This is the companion result, and the reason the recursive/CPS path is not a
cop-out but is *work-optimal* for ambiguous bodies. It is an argument rather than a
fully formalised proof (it depends on a reasonable notion of "the work a matcher
must do"), and §(ii) tests it empirically.

> Any **complete** backtracking matcher for `B*` with an *ambiguous* body must, in
> the worst case, **re-evaluate `B` at positions it has already matched**. Backoffs
> avoids re-evaluation only by exploiting fixed-width determinism, which an
> ambiguous body lacks. Re-evaluating `B` from a mid-loop position, parameterised by
> what to match after the loop, *is* the loop continuation — i.e. CPS/recursion.
> Hence no mechanism that stays correct on ambiguous bodies can avoid the work the
> CPS/recursive path already does; it can only add overhead on top.

**Argument.** Backtracking semantics requires enumerating alternative matches of
the body in greedy order.

- *Fixed-width, unambiguous body.* The only alternatives are different *counts* of
  iterations. The resulting positions form the arithmetic progression `Reachable`,
  so an alternative is reached by **arithmetic alone** (`finalPos − i·w`) — no
  re-matching. This is exactly why Backoffs is fast (and why `count` compresses a
  whole run into one frame).

- *Ambiguous body.* Alternatives now include matching the *same* iterations
  *differently*. Flipping iteration `j` from branch `w₁` to branch `w₂` shifts the
  start of iteration `j+1` from `p_j + w₁` to `p_j + w₂`, which **invalidates every
  later iteration**: they must be re-matched from the new position. Re-matching the
  tail of the loop from a position, knowing what must follow the loop, is precisely
  an invocation of the body matcher under the loop's continuation. So any complete
  mechanism contains (the equivalent of) continuation-passing body re-evaluation,
  and performs *at least* the re-matching work the CPS/recursive path performs.

**Consequence.** A "generalised Backoffs" that records per-iteration branch choices
cannot turn backtracking back into arithmetic: to act on a flipped choice it must
re-run the body from the affected position (needing the continuation). It therefore
**collapses into the recursive path**, plus the overhead of maintaining the choice
records — strictly worse, never better. The recursive continuation path is the
work-optimal complete mechanism for ambiguous bodies; Backoffs' advantage is
*fundamentally confined* to the fixed-width-unambiguous regime of Theorem 1.

**Empirical confirmation (§ii).** `ChoiceBackoffsCollapseProbe` implements exactly
this choice-augmented Backoffs for `(w₀|w₁|…)* T` and compares it to a plain
recursive backtracker, both instrumented with a body-evaluation counter. They agree
on every short string, and the augmented matcher performs the **identical** number
of body evaluations as recursion (e.g. `(a|ab)*c` on `"ab"×8+"c"`: 43 vs 43; on
`"a"×16+"c"`: 19 vs 19) — it re-runs `forward` on each branch flip, i.e. it is the
recursive backtracker, plus the cost of maintaining the frame records. No win.

## CPS is the substrate; Backoffs is a body-re-matching elimination

It is tempting to read Backoffs and CPS as two alternative engines. They are not. The
recursive path *is* staged CPS — it threads a continuation `k` per instruction and
inlines it away at compile time. And even **Backoffs threads the loop's *exit*
continuation**: `exit(pos)` is "what must match after the loop", carrying `k`. So the
continuation is the substrate *everywhere*.

What Backoffs uniquely does is eliminate the need to **re-match the body** to find the
next loop-end position: when the body is fixed-width and unambiguous, those positions
form an arithmetic progression (`finalPos − i·width`), so backtracking the loop is
arithmetic instead of re-running the body. That elimination is sound exactly on the
Theorem 1 boundary, and Theorem 2 says it is the *only* place a complete mechanism can
beat CPS. Off the boundary, re-matching is unavoidable, and re-matching-under-`k` *is*
CPS — so you are doing CPS by necessity.

So the architecture is **one CPS substrate with a Backoffs fast-path layered on top**:

```
bodyNeedsRecursivePath(B)  ==  ¬(B is fixed-width and unambiguous)   (Theorem 1)
```

- `false` ⟹ Backoffs: still threads the exit continuation, but replaces body
  re-matching with arithmetic — complete (Thm 1) and cheaper (Thm 2) *when the loop is the
  outer/standalone control*. As an inner loop re-entered under a recursive outer loop its
  per-run allocation can make it a net loss — see Empirical results §3.
- `true`  ⟹ recursive path = staged CPS: complete and work-optimal (Thm 2); a VM or a
  choice-augmented Backoffs only adds overhead.

The two **compose freely through the shared continuation `k`** — a Backoffs loop's
`exit` may run a recursive loop, and a recursive loop's body or exit may contain
Backoffs loops. `StagedHybridCompositionFuzz` checks the staged hybrid against CPS over
all short strings for such mixtures (Backoffs→recursive, recursive-wrapping-Backoffs,
alternations of both, nested-recursive-then-Backoffs): full agreement.

## What this says about needing CPS

- **CPS the *technique* (continuation threading): required, permanently.** It is the
  substrate; Theorem 2 says ambiguous bodies cannot be matched without it. Backoffs does
  not remove it — Backoffs is layered on top and still uses it for the exit.
- **`CPSMatcher` the *engine*: redundant.** That technique already lives inside the Prog
  recursive path; a separate Pattern-based engine is useful only as a differential test
  oracle. Prog subsumes CPS by *internalising* it, not by avoiding it.

## Empirical results (JMH)

Three JMH suites (in `benchmark/`) compare the staged Prog matcher, the staged CPS
matcher (`StagedEngines` exposes both for the same regex), and `java.util.regex` as an
independent reference. The headline claim "Prog dominates CPS" survives, but the data
**refines it into three regimes** and turns up one case where Prog is genuinely *worse*.
All figures are AverageTime µs/op (lower is better) on one machine/JVM — treat them as
ratios, not absolutes.

### 1. Backoffs regime: Prog strictly beats CPS — by a constant *and* a complexity class

`EngineScalingBenchmark` sweeps input length over fixed-width unambiguous loops:

| pattern | n | Prog | CPS | CPS/Prog |
|---|---:|---:|---:|---:|
| `[a-z]*#` | 1024 | 2.68 | 4.51 | 1.68× |
| `[a-z]*#` | 16384 | 43.0 | 76.3 | 1.77× |
| `(abc)*d` | 1024 | 0.90 | 1.66 | 1.84× |
| `(abc)*d` | 16384 | 14.4 | 29.5 | 2.06× |

The ratio is **stable across a 64× size range** (~1.8–2×): both are linear, Prog has the
better constant (arithmetic backtrack + tight `@tailrec` loop vs CPS's per-iteration
method call). More importantly the difference is **also a complexity class in stack
usage**: CPS's `Rep0` recurses non-tail (one frame per iteration, O(n) stack), so
`EngineStackRobustnessTests` shows CPS **StackOverflows at n ≈ 131 072** on `a*` while
Prog's O(1)-stack Backoffs matches **1 000 000+** fine. That is the sharpest sense in
which CPS is strictly worse here.

The GC profiler (`-prof gc`) **corrects a tempting but wrong mechanism**: CPS does *not*
lose on allocation. Staging inlines its continuations, so CPS allocates ≈0 B/op; Prog
allocates a *constant* 40 B/op (the count-compressed Backoffs frame + the
`(Backoffs|Null, Int)` tuple). The win is call/stack overhead, not garbage.

### 2. Recursive regime, no inner Backoffs loop: Prog = CPS (confirms Theorem 2)

For ambiguous bodies whose branches are themselves fixed (`(a|b)*#`,
`(?:foo|bar|baz|qux)+`), Prog drops to its recursive path, which *is* staged CPS. The
benchmarks agree to within noise at every size (e.g. `(a|b)*#`: 1.01–1.04× across
256–16384; `altkw`: 3.39 vs 3.40). Both allocate ≈0. This is the experimental control
for Theorem 2, and it behaves exactly as predicted: off the boundary, Backoffs can
neither help nor hurt.

### 3. Recursive regime *with* an inner Backoffs loop under heavy re-entry: Prog < CPS

This is the **new finding the theory implied but no earlier test exposed**.
`ComplexPatternBenchmark`'s `(?:a+)+b` on `"a"*20` (nested loop ⇒ recursive outer, with
the inner `a+` on the Backoffs fast-path) fails and backtracks exponentially:

| | Prog | CPS | Java |
|---|---:|---:|---:|
| time (µs/op) | 3675 | **2132** | 1.50 |
| alloc (B/op) | **6 291 505** | ≈0 | 808 |

Prog is **1.7× slower than CPS and allocates 6.3 MB/op**. Cause: the inner `a+` Backoffs
loop is re-run on every step of the outer exponential search, and *each* re-run pays
Backoffs' frame + tuple allocation — overhead that staged CPS (pure stack recursion) does
not have. This is precisely the "collapses into the recursive path, plus the overhead of
maintaining the frame records — strictly worse, never better" consequence of Theorem 2,
now observed in the *production* engine, not just the choice-augmented probe.

**Refinement to the architecture claim.** Backoffs is a net win only when its loop is the
**outer/standalone** control. As an **inner** loop re-entered under an ambiguous outer
loop, its allocation makes Prog *worse* than plain CPS. The completeness boundary
(Theorem 1) is unchanged; what shifts is the *performance* story: `bodyNeedsRecursivePath`
correctly routes the outer loop to recursion, but it still lets the inner fixed-width loop
use allocating Backoffs, which is the liability. Making Backoffs allocation-free (or
suppressing it for inner loops under a recursive ancestor) would close this gap — see the
memory-efficiency work.

### vs `java.util.regex`

On **structured** patterns Prog beats Java 2–3.4× (`email` 2.4×, `csv` 3.3×, `path` 2.5×,
`quad` 3.4×). Java wins only (a) on a bare single-class loop `[a-z]*#` (~1.5–2.5×, almost
certainly a JDK intrinsic) and (b) spectacularly on `(?:a+)+b` (1.5 µs — it clearly does
not perform the 2¹⁹ walk our naive backtracker does). Two honest caveats: Oregano has **no
ReDoS mitigation**, and Java itself failed to complete `(a|b)*#` at n ≥ 4096 (its own
long-input blowup), where both Oregano engines stayed linear.
