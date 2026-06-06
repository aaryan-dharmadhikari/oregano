/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.TableDrivenPropertyChecks.*
import oregano.internal.StagedMatchers.{stagedProg, stagedCPS}

/** Documents the completeness boundary of the flat Backoffs optimisation in the
  * Prog backtracking engine (see the `bodyNeedsRecursivePath` proof comment in
  * BacktrackingProgMatcher).
  *
  * Theorem: Backoffs is complete iff the loop body is fixed-width and unambiguous.
  *   - ON the Backoffs side (fixed-width, unambiguous): the loop's valid end
  *     positions are exactly the greedy chain, so Backoffs is complete.
  *   - OFF it (alternation of unequal widths, or a nested loop): valid end positions
  *     lie on chains the greedy pass never recorded, so pure Backoffs is INCOMPLETE
  *     and the engine must fall back to the recursive choice-point path.
  *
  * These tests assert correct behaviour on BOTH sides via `stagedProg`, and pin the
  * two witnesses from the proof. The ambiguous cases are also cross-checked against
  * the CPS engine, whose semantics are the ground truth.
  */
class BackoffsCompletenessBoundaryTests extends AnyFlatSpec {

  // ── ON the boundary: fixed-width, unambiguous bodies — handled by Backoffs ──
  behavior of "Backoffs side (fixed-width unambiguous loop bodies)"

  it should "match fixed-width loops correctly (these stay on the Backoffs path)" in {
    val cases = Table(
      ("regex", "input", "expected"),
      ("(ab)*c",   "c",        true),
      ("(ab)*c",   "abc",      true),
      ("(ab)*c",   "ababc",    true),
      ("(ab)*c",   "ab",       false), // no trailing c
      ("(abc)*d",  "abcabcd",  true),
      ("(abc)*d",  "abc",      false),
      ("a*b",      "aaab",     true),
      ("a*b",      "a",        false)
    )
    forAll(cases) { (re, in, exp) =>
      val m = re match
        case "(ab)*c"  => stagedProg("(ab)*c")
        case "(abc)*d" => stagedProg("(abc)*d")
        case "a*b"     => stagedProg("a*b")
      withClue(s"$re on '$in'") { m(in) shouldBe exp }
    }
  }

  // ── OFF the boundary: ambiguous bodies — pure Backoffs is provably incomplete ──
  // The engine routes these to the recursive path; here we assert it gets the
  // RIGHT answer, i.e. the answer Backoffs alone could not produce.
  behavior of "recursive side (ambiguous loop bodies Backoffs cannot complete)"

  // Witness 1: alternation of unequal widths.
  // Greedy `forward` takes "a" => chain {0,1}; the match needs the continuation
  // `c` at position 2 (chain {0,2}), which Backoffs never reaches.
  it should "match (a|ab)*c where the suffix sits off the greedy chain" in {
    val prog = stagedProg("(a|ab)*c")
    val cps  = stagedCPS("(a|ab)*c")
    forAll(Table("input", "abc", "aabc", "ababc", "ac", "c", "ab", "")) { s =>
      withClue(s"(a|ab)*c on '$s'") { prog(s) shouldBe cps(s) }
    }
    prog("abc") shouldBe true // the headline witness
  }

  // Witness 2: nested loop. Greedy outer iteration eats "bb" (width 2) => chain
  // {2,0}; the match needs position 1 (inner loop giving one `b` back), off it.
  it should "match (b*)*bc where the suffix sits off the width-2 greedy chain" in {
    val prog = stagedProg("(b*)*bc")
    val cps  = stagedCPS("(b*)*bc")
    forAll(Table("input", "bc", "bbc", "bbbbc", "b", "", "bbcd")) { s =>
      withClue(s"(b*)*bc on '$s'") { prog(s) shouldBe cps(s) }
    }
    prog("bbc") shouldBe true // the headline witness
  }
}
