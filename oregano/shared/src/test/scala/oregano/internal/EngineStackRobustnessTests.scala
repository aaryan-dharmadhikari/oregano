/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package oregano.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import oregano.internal.StagedMatchers.{stagedProg, stagedCPS}

/** Stack-depth robustness: the qualitative difference behind the throughput gap.
  *
  * For a fixed-width unambiguous loop `B*`, the staged Prog matcher uses the Backoffs
  * fast-path, whose `forward` scan is `@tailrec` — O(1) stack regardless of how many
  * iterations match. The CPS matcher's `Rep0` recurses through `self` in non-tail
  * position (CPSMatcher.scala): one JVM stack frame PER ITERATION, i.e. O(n) stack. So
  * on a long enough input CPS throws StackOverflowError while Prog matches fine. This is
  * not a constant factor — it is a complexity-class difference in stack usage, and the
  * sharpest sense in which CPS is strictly worse than Prog inside the Backoffs regime.
  */
class EngineStackRobustnessTests extends AnyFlatSpec {

  private val prog = stagedProg("a*")
  private val cps  = stagedCPS("a*")

  /** true iff `m` returns (any result) without blowing the stack on "a"*n. */
  private def survives(m: CharSequence => Boolean, n: Int): Boolean = {
    val input = "a" * n
    try { m(input); true }
    catch { case _: StackOverflowError => false }
  }

  it should "let Prog (Backoffs, tailrec) match an input far past CPS's stack limit" in {
    // Find where CPS first overflows on `a*` (doubling search), so the contrast is concrete.
    var n = 1024
    while (survives(cps, n) && n <= (1 << 24)) n *= 2
    val cpsOverflowAt = n
    info(s"CPS (a*) overflowed the stack at n=$cpsOverflowAt")

    // CPS must actually have a finite limit within our search window...
    cpsOverflowAt should be <= (1 << 24)
    // ...and Prog must comfortably exceed it (an order of magnitude past, capped at 4M).
    val progTarget = math.min(cpsOverflowAt.toLong * 10, 4_000_000L).toInt
    withClue(s"Prog should survive n=$progTarget (10x CPS's overflow point): ") {
      survives(prog, progTarget) shouldBe true
    }
    info(s"Prog (a*) matched n=$progTarget without overflowing")
  }

  it should "have Prog match a million-character input that CPS cannot" in {
    val n = 1_000_000
    withClue("Prog must match \"a\"*1e6: ") { survives(prog, n) shouldBe true }
    withClue("CPS is expected to overflow on \"a\"*1e6: ") { survives(cps, n) shouldBe false }
  }
}
