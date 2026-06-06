/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package oregano.internal

/** A non-recursive backtracking matcher over the Prog IR, driven by an explicit
  * choice-point stack instead of the call stack (the "N-backoffs" idea: every
  * unexplored alternative is a frame on a stack, not a pending recursive call).
  *
  * This is the *runtime* reference realisation — anchored full match, boolean
  * (captures ignored for now) — used to validate the approach before staging it.
  * Two stacks' worth of work, folded into one frame type:
  *   - choice points (the failure continuation): at every greedy decision — an ALT
  *     (left vs right) or a LOOP (iterate vs exit) — we push the alternative we did
  *     NOT take, with the position to resume at. On a mismatch we pop the top frame,
  *     restore, and resume there.
  *   - completion (the success continuation) is implicit in the Prog graph: after a
  *     fragment matches we just follow `out`/`arg`, so no separate return stack is
  *     needed for this flat instruction tape.
  *
  * The one subtlety the recursive path gets for free (via its `next != p` guard) is
  * the empty-iteration guard for nullable loop bodies. Here we track each loop's
  * entry position in `loopEntry`, trailed on the choice stack so backtracking
  * restores it: arriving back at a LOOP at the same position means the body matched
  * empty, so we must exit rather than spin.
  */
object BacktrackVM {
    def matches(prog: Prog, input: CharSequence): Boolean = {
        val len = input.length

        // Dense id per LOOP instruction, for indexing loopEntry.
        val numInst = prog.numInst
        val loopId = new Array[Int](numInst)
        var numLoops = 0
        var i = 0
        while (i < numInst) {
            if (prog.getInst(i).op == InstOp.LOOP) { loopId(i) = numLoops; numLoops += 1 }
            i += 1
        }
        val loopEntry = Array.fill(if (numLoops == 0) 1 else numLoops)(-1)

        // Choice-point stack as parallel arrays.
        var cap = 16
        var csPc    = new Array[Int](cap)
        var csPos   = new Array[Int](cap)
        var csLoop  = new Array[Int](cap) // loop id whose entry to restore, or -1
        var csSaved = new Array[Int](cap) // previous loopEntry value
        var sp = 0
        def push(pc: Int, pos: Int, loop: Int, saved: Int): Unit = {
            if (sp == cap) {
                cap *= 2
                csPc    = java.util.Arrays.copyOf(csPc, cap)
                csPos   = java.util.Arrays.copyOf(csPos, cap)
                csLoop  = java.util.Arrays.copyOf(csLoop, cap)
                csSaved = java.util.Arrays.copyOf(csSaved, cap)
            }
            csPc(sp) = pc; csPos(sp) = pos; csLoop(sp) = loop; csSaved(sp) = saved
            sp += 1
        }

        var pc = prog.start
        var pos = 0
        var matched = false
        var done = false
        while (!done) {
            val inst = prog.getInst(pc)
            var fail = false
            inst.op match {
                case InstOp.MATCH => if (pos == len) { matched = true; done = true } else fail = true
                case InstOp.FAIL  => fail = true
                case InstOp.RUNE | InstOp.RUNE1 | InstOp.RUNE_ANY | InstOp.RUNE_ANY_NOT_NL =>
                    if (pos < len && inst.matchRune(input.charAt(pos).toInt)) { pos += 1; pc = inst.out }
                    else fail = true
                case InstOp.NOP | InstOp.CAPTURE | InstOp.EMPTY_WIDTH => pc = inst.out
                case InstOp.ALT | InstOp.ALT_MATCH =>
                    push(inst.arg, pos, -1, 0)  // try `out` first, `arg` on backtrack
                    pc = inst.out
                case InstOp.LOOP =>
                    val id = loopId(pc)
                    if (loopEntry(id) == pos) pc = inst.arg // body matched empty -> exit, don't spin
                    else {
                        val saved = loopEntry(id)
                        loopEntry(id) = pos
                        push(inst.arg, pos, id, saved)      // exit alternative (restores loopEntry)
                        pc = inst.out                       // greedily enter the body
                    }
            }
            if (fail) {
                if (sp > 0) {
                    sp -= 1
                    pc = csPc(sp); pos = csPos(sp)
                    if (csLoop(sp) >= 0) loopEntry(csLoop(sp)) = csSaved(sp)
                } else done = true
            }
        }
        matched
    }
}
