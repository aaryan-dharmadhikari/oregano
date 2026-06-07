/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*

/** Experiment (ii) from docs/backoffs-completeness.md: try to extend Backoffs to an
  * ambiguous body — an alternation of unequal-width literals — by recording each
  * iteration's branch choice, and watch it COLLAPSE into the recursive path.
  *
  * Models `(w0|w1|...)* T` (anchored full match, boolean). Two matchers share a body
  * -evaluation counter (each "does branch wᵢ match at position p?" test). The point:
  * Backoffs' speed came from backtracking via pure arithmetic (drop iterations, no
  * re-match). Here, to act on a flipped branch choice the augmented matcher must
  * re-run `forward` from the changed position — re-matching the tail. That re-run IS
  * the loop continuation; the mechanism is the recursive backtracker plus the cost of
  * maintaining the choice records. So: same matching work, extra bookkeeping — never
  * better. (Theorem 2.)
  */
object ChoiceBackoffsCollapseProbe {

  /** Plain recursive (CPS-shaped) greedy backtracker — the baseline. */
  def recursive(branches: List[String], cont: String, input: String): (Boolean, Int) = {
    val len = input.length
    var evals = 0
    def hit(s: String, p: Int): Boolean = {
      evals += 1
      p + s.length <= len && input.regionMatches(p, s, 0, s.length)
    }
    def contOk(p: Int): Boolean = hit(cont, p) && p + cont.length == len
    def go(pos: Int): Boolean = {
      var b = 0
      while (b < branches.length) {
        val w = branches(b)
        if (w.nonEmpty && hit(w, pos) && go(pos + w.length)) return true
        b += 1
      }
      contOk(pos) // exit the loop here (zero further iterations)
    }
    (go(0), evals)
  }

  /** Backoffs augmented with per-iteration branch records. forward greedily takes the
    * lowest matching branch each step; backtrack tries the continuation, then flips the
    * last iteration's branch to a later one (RE-MATCHING the tail via forward — the
    * collapse), then drops the iteration. */
  def augmented(branches: List[String], cont: String, input: String): (Boolean, Int) = {
    val len = input.length
    var evals = 0
    def hit(s: String, p: Int): Boolean = {
      evals += 1
      p + s.length <= len && input.regionMatches(p, s, 0, s.length)
    }
    def contOk(p: Int): Boolean = hit(cont, p) && p + cont.length == len

    val startPos  = scala.collection.mutable.ArrayBuffer.empty[Int] // frame: iteration start
    val branchIdx = scala.collection.mutable.ArrayBuffer.empty[Int] // frame: branch taken

    // Greedy forward from `from`, pushing a frame per iteration; returns the end position.
    def forward(from: Int): Int = {
      var pos = from
      var progressed = true
      while (progressed) {
        progressed = false
        var b = 0
        while (b < branches.length && !progressed) {
          val w = branches(b)
          if (w.nonEmpty && hit(w, pos)) {
            startPos += pos; branchIdx += b; pos += w.length; progressed = true
          }
          b += 1
        }
      }
      pos
    }

    var endPos = forward(0)
    var result = false
    var searching = true
    while (searching) {
      if (contOk(endPos)) { result = true; searching = false }
      else if (startPos.isEmpty) searching = false // 0 iterations tried and failed -> exhausted
      else {
        val sp = startPos.last
        // try to flip this iteration to a later branch that also matches here
        var nb = branchIdx.last + 1
        var flipped = false
        while (nb < branches.length && !flipped) {
          val w = branches(nb)
          if (w.nonEmpty && hit(w, sp)) {
            // drop frames from this one onward, re-take this branch, and RE-RUN forward
            // for the tail. <<< THE COLLAPSE: backtrack re-invokes forward (re-matching) >>>
            startPos.remove(startPos.length - 1); branchIdx.remove(branchIdx.length - 1)
            startPos += sp; branchIdx += nb
            endPos = forward(sp + w.length)
            flipped = true
          }
          nb += 1
        }
        if (!flipped) {
          startPos.remove(startPos.length - 1); branchIdx.remove(branchIdx.length - 1)
          endPos = sp // fewer iterations: try the continuation at this iteration's start
        }
      }
    }
    (result, evals)
  }
}

class ChoiceBackoffsCollapseProbe extends AnyFlatSpec {
  import ChoiceBackoffsCollapseProbe.*

  private def allStrings(alphabet: String, maxLen: Int): Seq[String] = {
    val cs = alphabet.map(_.toString)
    def go(n: Int): Seq[String] = if (n == 0) Seq("") else for { p <- go(n - 1); c <- cs } yield p + c
    (0 to maxLen).flatMap(go)
  }

  private val families = Seq(
    (List("a", "ab"), "c", "abc"),
    (List("ab", "a"), "c", "abc"),
    (List("a", "b"),  "c", "abc"),
    (List("a", "aa"), "b", "ab"),
    (List("a", "bb"), "c", "abc"),
    (List("ab", "abc"), "d", "abcd")
  )

  it should "agree with the recursive backtracker on all short strings (correctness)" in {
    for ((branches, cont, alphabet) <- families) {
      for (s <- allStrings(alphabet, 6)) {
        val (a, _) = augmented(branches, cont, s)
        val (r, _) = recursive(branches, cont, s)
        withClue(s"branches=$branches cont=$cont on '$s'") { a shouldBe r }
      }
    }
  }

  it should "show augmented Backoffs does no less matching work than recursion (the collapse)" in {
    // Worst-case-ish inputs: long, ending so the loop must backtrack to complete.
    val cases = Seq(
      (List("a", "ab"), "c", "ab" * 8 + "c"),
      (List("a", "ab"), "c", "a" * 16 + "c"),
      (List("a", "aa"), "b", "a" * 16 + "b")
    )
    println("%-22s %-20s %10s %10s".format("branches", "input", "rec evals", "aug evals"))
    for ((branches, cont, input) <- cases) {
      val (ra, re) = recursive(branches, cont, input)
      val (aa, ae) = augmented(branches, cont, input)
      ra shouldBe aa // same answer
      println("%-22s %-20s %10d %10d".format(branches.mkString("|"), input, re, ae))
      withClue(s"augmented should not undercut recursion for $branches on '$input'") {
        ae should be >= re // never fewer body evaluations -> no win, only bookkeeping overhead
      }
    }
    succeed
  }
}
