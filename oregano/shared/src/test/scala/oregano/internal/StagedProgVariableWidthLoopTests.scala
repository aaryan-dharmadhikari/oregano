/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.TableDrivenPropertyChecks.*
import oregano.internal.StagedMatchers.stagedProg

/** A loop body does not have to *nest a loop* to defeat the flat Backoffs path:
  * an alternation whose branches have different widths is enough. Backoffs records
  * only the greedy width of each iteration and backtracks by dropping whole
  * iterations, so it never tries "this iteration via the other (longer) branch".
  *
  * These drive the Prog engine directly via `stagedProg` and must agree with the
  * CPS engine's (correct) semantics.
  */
class StagedProgVariableWidthLoopTests extends AnyFlatSpec {

  // ---- (a|ab)*c : branches of differing width — the core regression ----
  val altWidths = stagedProg("(a|ab)*c")

  behavior of "StagedProgMatcher - (a|ab)*c (variable-width alternation body)"

  it should "match when completing requires choosing the longer branch" in {
    val valid = Table("input", "c", "ac", "abc", "aabc", "ababc", "aababc")
    forAll(valid) { s =>
      withClue(s"Failed on input: $s") { altWidths(s) shouldBe true }
    }
  }

  it should "reject strings that cannot complete with a trailing c" in {
    val invalid = Table("input", "", "a", "ab", "b", "cc", "abca")
    forAll(invalid) { s =>
      withClue(s"Incorrectly matched input: $s") { altWidths(s) shouldBe false }
    }
  }

  // ---- (ab|a)*c : same language, greedy tries the longer branch first ----
  val altWidthsRev = stagedProg("(ab|a)*c")

  behavior of "StagedProgMatcher - (ab|a)*c (reversed alternative order)"

  it should "match the same language regardless of branch order" in {
    val valid = Table("input", "c", "ac", "abc", "aabc", "ababc")
    forAll(valid) { s =>
      withClue(s"Failed on input: $s") { altWidthsRev(s) shouldBe true }
    }
  }

  it should "reject strings without a trailing c" in {
    val invalid = Table("input", "", "a", "ab", "abca")
    forAll(invalid) { s =>
      withClue(s"Incorrectly matched input: $s") { altWidthsRev(s) shouldBe false }
    }
  }

  // ---- (a|b)*c : equal-width alternation — already works, guards against regression ----
  val altEqualWidth = stagedProg("(a|b)*c")

  behavior of "StagedProgMatcher - (a|b)*c (equal-width alternation body)"

  it should "keep matching equal-width alternation loops" in {
    val valid = Table("input", "c", "ac", "bc", "abc", "bac", "ababbac")
    forAll(valid) { s =>
      withClue(s"Failed on input: $s") { altEqualWidth(s) shouldBe true }
    }
  }

  it should "reject equal-width alternation loops without a trailing c" in {
    val invalid = Table("input", "", "ab", "ba", "abd")
    forAll(invalid) { s =>
      withClue(s"Incorrectly matched input: $s") { altEqualWidth(s) shouldBe false }
    }
  }
}
