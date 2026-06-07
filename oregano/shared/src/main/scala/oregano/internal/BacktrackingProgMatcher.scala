/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package oregano.internal

import scala.quoted.*

private object BacktrackingProgMatcher {
    // TODO: encapsulate this state properly
    /** Is the flat Backoffs fast-path UNSOUND for this loop body (the region
      * reachable from `start`, bounded by `end` = the LOOP instruction itself)?
      *
      * == Completeness boundary of Backoffs ==
      *
      * Backoffs does one greedy `forward` pass, records it as `(width, count)`
      * frames, and `backtrack` only ever drops trailing iterations (it tries the
      * loop's continuation at `finalPos - i*width`). So the only loop-end positions
      * it can ever try are the prefixes of that ONE greedy run:
      *     Reachable = { p0=start, p1, p2, ..., pn=finalPos }, p_{k+1} = p_k + width_k
      * It stores the WIDTHS (the result of each choice), never the CHOICES.
      *
      * Theorem: Backoffs is complete iff the body is fixed-width and unambiguous
      * (every position matches the body in at most one way, at a context-independent
      * width).
      *   - (sufficiency) Fixed width w + unique match => the only positions the loop
      *     can legitimately stop at ARE {start, start+w, start+2w, ...}, i.e. exactly
      *     Reachable, visited longest-first. Nothing is missed.
      *   - (necessity) If the body can match at some position two ways with widths
      *     w1 < w2 (an alternation of unequal-length branches) or a nested LOOP that
      *     can give reps back, the valid loop-end positions lie on MULTIPLE chains,
      *     one per sequence of choices. Greedy `forward` records a single chain C;
      *     `backtrack` only visits C; a match on another chain C' is unreachable.
      *     Witnesses: `(a|ab)*c` on "abc" needs the continuation at position 2 (chain
      *     {0,2}), but greedy takes "a" => chain {0,1}, and 2 is off it; `(b*)*bc` on
      *     "bbc" needs position 1, off the width-2 greedy chain {2,0}.
      * Fixing this requires storing per-iteration choices (O(n) of them), which
      * destroys the (width,count) compression: it becomes a choice-point stack
      * (the recursive/continuation path), categorically more than Backoffs.
      *
      * So: return true (=> use the recursive path) for any body that is not provably
      * fixed-width-unambiguous. Conservative: ANY alternation is treated as unsafe,
      * even equal-width mutually-exclusive ones like `(a|b)` that Backoffs could in
      * fact handle; unknown ops also fall through to "unsafe", since the recursive
      * path is always correct and Backoffs is only an optimisation.
      */
    private def bodyNeedsRecursivePath(prog: Prog, start: Int, end: Int): Boolean = {
        val seen = scala.collection.mutable.Set.empty[Int]
        def go(pc: Int): Boolean =
            if (pc == end || seen(pc)) false
            else {
                seen += pc
                val inst = prog.getInst(pc)
                inst.op match
                    case InstOp.ALT | InstOp.LOOP => true
                    // fixed-width, deterministic, single successor: safe to summarise
                    case InstOp.RUNE | InstOp.RUNE1 | InstOp.RUNE_ANY
                       | InstOp.RUNE_ANY_NOT_NL | InstOp.CAPTURE
                       | InstOp.NOP | InstOp.EMPTY_WIDTH => go(inst.out)
                    case _ => true
            }
        go(start)
    }

    // `k` is the continuation: the staged "rest of the match" invoked when control
    // reaches `end`. Threading it lets an inner loop's exit see the outer context
    // (the bug with nested loops was that exits at a loop boundary just returned `pos`).
    private def compile(prog: Prog, pc: Int, end: Int, input: Expr[CharSequence], noCaps: Int, pos: Expr[Int], cap: Option[Expr[Array[Int]]], wholeMatch: Boolean, k: Expr[Int] => Expr[Int])(using Quotes): Expr[Int] = {
        if (pc == end) k(pos)
        else {
            val inst = prog.getInst(pc)
            inst.op match
                case InstOp.MATCH => cap match
                    case None => if wholeMatch then '{ if $pos == $input.length then $pos else -1 } else pos
                    case Some(cap) =>
                        if wholeMatch then '{
                            if ($pos == $input.length) {
                                $cap(1) = $pos
                                $pos
                            } else -1
                        }
                        else '{
                            $cap(1) = $pos
                            $pos
                        }
                case InstOp.FAIL => '{ -1 }

                case InstOp.ALT =>
                    val leftExpr = compile(prog, inst.out, end, input, noCaps, pos, cap, wholeMatch, k)
                    val rightExpr = compile(prog, inst.arg, end, input, noCaps, pos, cap, wholeMatch, k)
                    '{
                        val lp = $leftExpr
                        if lp >= 0 then lp else $rightExpr
                    }

                case InstOp.RUNE | InstOp.RUNE1 =>
                    val runeCheck = inst.matchRuneExpr
                    val nextPos = '{ $pos + 1 }
                    val succExpr = compile(prog, inst.out, end, input, noCaps, nextPos, cap, wholeMatch, k)
                    val charExpr: Expr[Int] = '{ $input.charAt($pos).toInt }
                    val condExpr: Expr[Boolean] = runeCheck(charExpr)

                    '{ if ($pos < $input.length && $condExpr) then $succExpr else -1 }

                // Variable-width / ambiguous body (nested loop or alternation):
                // Backoffs treats each iteration atomically and cannot give characters
                // back to a later subexpression, so fall back to a recursive,
                // continuation-threaded greedy star. This mirrors the CPS engine's
                // Rep0, but stays a staged matcher over the Prog IR. Relies on quote
                // hygiene: `loop`/`p` in the continuation bind to this quote's bindings,
                // not to anything that shadows them at the inner splice site.
                case InstOp.LOOP if bodyNeedsRecursivePath(prog, inst.out, pc) => '{
                    def exit(p: Int): Int = ${compile(prog, inst.arg, end, input, noCaps, 'p, cap, wholeMatch, k)}
                    def loop(p: Int): Int = {
                        // If the body ADVANCES (next != p) keep iterating; if it matches EMPTY
                        // (next == p) we must NOT recurse (infinite loop) — but unlike a plain
                        // `-1` here, we run the loop EXIT at that position so the one empty
                        // iteration's captures survive (java/PCRE keep the last, empty, iteration's
                        // group writes; returning -1 would unwind them in the CAPTURE handler).
                        val step = ${compile(prog, inst.out, pc, input, noCaps, 'p, cap, wholeMatch,
                            (next: Expr[Int]) => '{ if ($next != p) loop($next) else exit($next) })}
                        if (step >= 0) step
                        else exit(p)
                    }
                    loop($pos)
                }

                case InstOp.LOOP => '{
                    def exit(p: Int): Int = ${compile(prog, inst.arg, end, input, noCaps, 'p, cap, wholeMatch, k)}

                    // Greedy forward scan. `bodyNeedsRecursivePath` guarantees the body is a
                    // straight-line rune chain (no ALT, no nested LOOP), so every iteration
                    // advances by the SAME width. The old (width, count) Backoffs frame list
                    // could therefore only ever hold a single frame with a null parent — so we
                    // drop the linked list AND the Tuple2 return entirely and track the run with
                    // three primitive `var`s. This makes the Backoffs path allocation-free.
                    val start: Int = $pos
                    var scanPos: Int = start
                    var count: Int = 0
                    var width: Int = 0
                    var scanning: Boolean = true
                    while (scanning) {
                        val next: Int = ${compile(prog, inst.out, pc, input, noCaps, 'scanPos, cap, wholeMatch, (p: Expr[Int]) => p)}
                        if (next == -1 || next == scanPos) scanning = false
                        else {
                            width = next - scanPos   // constant across iterations (fixed-width body)
                            count += 1
                            scanPos = next
                        }
                    }
                    val finalPos = scanPos

                    // Backtrack by dropping trailing iterations: try the continuation at
                    // finalPos, finalPos-width, ..., down to start (== finalPos - count*width,
                    // the i==count case, which is the old `parent == null -> exit(start)` step).
                    var i = 0
                    var result = -1
                    while (result < 0 && i <= count) {
                        result = exit(finalPos - i * width)
                        i += 1
                    }
                    result
                }

                case InstOp.CAPTURE =>
                    val slot = inst.arg
                    val nextExp = compile(prog, inst.out, end, input, noCaps, pos, cap, wholeMatch, k)

                    cap match {
                        case Some(cap) if slot < noCaps =>
                            val slotIdx: Expr[Int] = Expr(slot)
                            // Write the slot BEFORE matching the rest, and leave it on success.
                            // (Writing it after success makes an outer loop iteration's value
                            // clobber inner ones during unwind, recording the first iteration
                            // instead of the last — wrong under repetition. Mirrors CPSMatcher.)
                            '{
                                val oldVal = $cap($slotIdx)
                                $cap($slotIdx) = $pos
                                val res = $nextExp
                                if (res >= 0) res
                                else {
                                    $cap($slotIdx) = oldVal
                                    -1
                                }
                            }
                        case _ => '{
                            val res = $nextExp
                            if (res >= 0) then res else -1
                        }
                    }

                case _ => quotes.reflect.report.errorAndAbort(s"Unsupported op: ${inst.op}")
        }
    }

    def genMatcher(prog: Prog)(using Quotes): Expr[CharSequence => Boolean] = '{ (input: CharSequence) =>
        val result: Int = ${compile(prog, prog.start, prog.numInst, 'input, 0, '{ 0 }, cap = None, wholeMatch = true, k = (p: Expr[Int]) => p)}
        result == input.length
    }

    def genMatcherWithCaps(prog: Prog)(using Quotes): Expr[CharSequence => Option[Array[Int]]] = '{ (input: CharSequence) =>
        val groups = Array.fill(${Expr(prog.numCap)})(-1)
        groups(0) = 0

        val result: Int = ${compile(prog, prog.start, prog.numInst, 'input, prog.numCap, '{ 0 }, cap = Some('groups), wholeMatch = true, k = (p: Expr[Int]) => p)}

        if result == input.length then Some(groups) else None
    }

    def genPrefixFind(prog: Prog)(using Quotes): Expr[(Int, CharSequence) => Int] = '{ (startPos: Int, input: CharSequence) =>
        /*val result: Int = */${compile(prog, prog.start, prog.numInst, 'input, 0, 'startPos, cap = None,  wholeMatch = false, k = (p: Expr[Int]) => p)}
        //result
    }

    // TODO: what is this for?
    /*def matches(prog: Prog, input: CharSequence): Boolean = {
        val cap = new Array[Int](prog.numCap)

        def compile(pc: Int, end: Int, pos: Int): Int = if (pc == end) pos else {
            val inst = prog.getInst(pc)
            inst.op match {
                case InstOp.MATCH =>
                    if (pos == input.length) {
                        cap(1) = pos // Capture full match end
                        pos
                    } else -1

                case InstOp.FAIL => -1
                case InstOp.ALT =>
                    val save = cap.clone()
                    val left = compile(inst.out, end, pos)
                    if (left >= 0) left
                    else {
                        Array.copy(save, 0, cap, 0, cap.length)
                        compile(inst.arg, end, pos)
                    }

                case InstOp.RUNE | InstOp.RUNE1 => if (pos < input.length && inst.matchRune(input.charAt(pos).toInt)) compile(inst.out, end, pos + 1) else -1

                case InstOp.LOOP =>
                    def loop(p: Int): Int = {
                        val save = cap.clone()
                        val next = compile(inst.out, pc, p)
                        if (next >= 0 && next != p) {
                            val inner = loop(next)
                            if (inner >= 0) inner
                            else {
                                Array.copy(save, 0, cap, 0, cap.length)
                                compile(inst.arg, end, p)
                            }
                        }
                        else compile(inst.arg, end, p)
                    }
                    loop(pos)

                case InstOp.CAPTURE =>
                    val slot = inst.arg
                    val nextPC = inst.out
                    if (slot % 2 == 0) {
                        val close = slot + 1
                        val oldStart = cap(slot)
                        val oldEnd = cap(close)
                        cap(slot) = pos
                        val result = compile(nextPC, end, pos)
                        if (result >= 0) {
                            cap(close) = result
                            result
                        }
                        else {
                            cap(slot) = oldStart
                            cap(close) = oldEnd
                            -1
                        }
                    } else {
                        val old = cap(slot)
                        cap(slot) = pos
                        val result = compile(nextPC, end, pos)
                        if (result < 0) cap(slot) = old
                        result
                    }

                case _ => throw new RuntimeException(s"Unsupported op: ${inst.op}")
            }
        }

        compile(prog.start, prog.numInst, 0) >= 0
    }*/
}
