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

## Design consequence

```
bodyNeedsRecursivePath(B)  ==  ¬(B is fixed-width and unambiguous)   (Theorem 1)
```

- `false` ⟹ Backoffs: complete (Thm 1) and strictly cheaper (no re-matching, Thm 2).
- `true`  ⟹ recursive continuation path: complete, and work-optimal (Thm 2) — a VM
  or a choice-augmented Backoffs would only add overhead.

So the hybrid is not a compromise; it is each regime's best mechanism, split along
the exact line where Backoffs stops being both complete and cheaper.
