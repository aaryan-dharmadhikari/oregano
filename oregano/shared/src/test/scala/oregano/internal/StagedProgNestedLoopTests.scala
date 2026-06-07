/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.TableDrivenPropertyChecks.*
import oregano.internal.StagedMatchers.{stagedProg, stagedProgWithCaps}

/** Nested-loop coverage for the staged backtracking (Prog/Backoffs) engine.
  *
  * `stagedProg` drives `BacktrackingProgMatcher` directly. The macro now stages this
  * same engine for every pattern (including these nested loops), so this is the engine
  * that ships. Expectations mirror the (passing) CPS nested-loop suites, so any
  * divergence here is a Prog bug, not a spec disagreement.
  */
class StagedProgNestedLoopTests extends AnyFlatSpec {

  // ---- ((a*)b*)*bc|(def) : same table as StagedCPSMatcherTests ----
  val nestedMatcher = stagedProg("((a*)b*)*bc|(def)")

  behavior of "StagedProgMatcher - regex ((a*)b*)*bc|(def)"

  it should "match valid strings for the first alternative ((a*)b*)*bc including backtracking" in {
    val validFirstAlt = Table(
      "input",
      "ababc",
      "aaaaabaababbc",
      "bc",
      "abbbbbc",
      "abababbbbbc",
      "abc",
      "abbbc"
    )

    forAll(validFirstAlt) { str =>
      withClue(s"Failed on input: $str") {
        nestedMatcher(str) shouldBe true
      }
    }
  }

  it should "match valid strings for the second alternative (def)" in {
    nestedMatcher("def") shouldBe true
  }

  it should "reject invalid or partial matches" in {
    val invalidInputs = Table(
      "input",
      "",
      "defg",
      "de"
    )

    forAll(invalidInputs) { str =>
      withClue(s"Incorrectly matched input: $str") {
        nestedMatcher(str) shouldBe false
      }
    }
  }

  // ---- (b*)*bc : the minimal failing case from the thesis (ch 4.1) ----
  val minimalNested = stagedProg("(b*)*bc")

  behavior of "StagedProgMatcher - regex (b*)*bc"

  it should "match b* followed by bc" in {
    val valid = Table("input", "bc", "bbc", "bbbbc", "bbbbbbc")
    forAll(valid) { str =>
      withClue(s"Failed on input: $str") {
        minimalNested(str) shouldBe true
      }
    }
  }

  it should "reject strings without a trailing bc" in {
    val invalid = Table("input", "", "b", "bbb", "c", "bbcd", "abc")
    forAll(invalid) { str =>
      withClue(s"Incorrectly matched input: $str") {
        minimalNested(str) shouldBe false
      }
    }
  }

  // ---- (a*)*b : nested star with a distinct trailing literal ----
  val nestedStarB = stagedProg("(a*)*b")

  behavior of "StagedProgMatcher - regex (a*)*b"

  it should "match a* followed by b" in {
    val valid = Table("input", "b", "ab", "aaab", "aaaaaaab")
    forAll(valid) { str =>
      withClue(s"Failed on input: $str") {
        nestedStarB(str) shouldBe true
      }
    }
  }

  it should "reject strings not ending in a single b" in {
    val invalid = Table("input", "", "a", "aaa", "abc", "bb")
    forAll(invalid) { str =>
      withClue(s"Incorrectly matched input: $str") {
        nestedStarB(str) shouldBe false
      }
    }
  }

  // ---- ((b*)*)*bc : triple nesting, exercises recursion through >1 level ----
  val tripleNested = stagedProg("((b*)*)*bc")

  behavior of "StagedProgMatcher - regex ((b*)*)*bc"

  it should "match b* followed by bc through three levels of nesting" in {
    val valid = Table("input", "bc", "bbc", "bbbbc", "bbbbbbbbc")
    forAll(valid) { str =>
      withClue(s"Failed on input: $str") {
        tripleNested(str) shouldBe true
      }
    }
  }

  it should "reject strings without a trailing bc (triple nesting)" in {
    val invalid = Table("input", "", "b", "bbb", "c", "bbcd")
    forAll(invalid) { str =>
      withClue(s"Incorrectly matched input: $str") {
        tripleNested(str) shouldBe false
      }
    }
  }

  // ---- ((a|aa)*)b : alternation inside a nested-capture loop, heavy backtracking ----
  val heavyBacktracking = stagedProg("((a|aa)*)b")

  behavior of "StagedProgMatcher - regex ((a|aa)*)b"

  it should "match runs of a followed by b regardless of how a/aa is chosen" in {
    val valid = Table("input", "b", "ab", "aab", "aaab", "aaaab", "aaaaaab", "aaaaaaaaaab")
    forAll(valid) { str =>
      withClue(s"Failed on input: $str") {
        heavyBacktracking(str) shouldBe true
      }
    }
  }

  it should "reject ambiguous prefixes that never reach the trailing b" in {
    val invalid = Table("input", "a", "aa", "aaa", "aaaaa", "ba", "c", "aac")
    forAll(invalid) { str =>
      withClue(s"Incorrectly matched input: $str") {
        heavyBacktracking(str) shouldBe false
      }
    }
  }

  // ---- captures through the Prog nested path; expectations mirror StagedCPSMatcherTests ----
  def checkCaps(
      matcher: CharSequence => Option[Array[Int]],
      cases: org.scalatest.prop.TableFor2[String, Option[Array[Int]]]
  ) =
    forAll(cases) { (input, expectedOpt) =>
      withClue(s"Input: '$input'") {
        (matcher(input), expectedOpt) match {
          case (Some(actual), Some(expected)) => actual.toList shouldBe expected.toList
          case (None, None)                   => succeed
          case (a, e)                         => fail(s"Expected: $e, got: $a")
        }
      }
    }

  val capsNestedLoops = stagedProgWithCaps("((a*)b*)*bc|(def)")

  behavior of "StagedProgMatcher - matchesWithCaps - ((a*)b*)*bc|(def)"

  it should "produce the same capture groups as the CPS engine" in {
    checkCaps(
      capsNestedLoops,
      Table(
        ("input", "expectedCaps"),
        ("a", None),
        ("ababc", Some(Array(0, 5, 3, 3, 3, 3, -1, -1))),
        ("abc", Some(Array(0, 3, 1, 1, 1, 1, -1, -1))),
        ("abbc", Some(Array(0, 4, 2, 2, 2, 2, -1, -1))),
        ("abbbc", Some(Array(0, 5, 3, 3, 3, 3, -1, -1))),
        ("aaaaabaababbc", Some(Array(0, 13, 11, 11, 11, 11, -1, -1))),
        ("bc", Some(Array(0, 2, 0, 0, 0, 0, -1, -1))),
        ("def", Some(Array(0, 3, -1, -1, -1, -1, 0, 3))),
        ("ababbbbabbbbabbabc", Some(Array(0, 18, 16, 16, 16, 16, -1, -1))),
        ("", None),
        ("defg", None),
        ("de", None)
      )
    )
  }

  val capsNestedAltLoops = stagedProgWithCaps("(((a)|b|cd)*)e")

  behavior of "StagedProgMatcher - matchesWithCaps - (((a)|b|cd)*)e"

  it should "produce the same capture groups as the CPS engine" in {
    checkCaps(
      capsNestedAltLoops,
      Table(
        ("input", "expectedCaps"),
        ("e", Some(Array(0, 1, 0, 0, -1, -1, -1, -1))),
        ("ae", Some(Array(0, 2, 0, 1, 0, 1, 0, 1))),
        ("abe", Some(Array(0, 3, 0, 2, 1, 2, 0, 1))),
        ("cde", Some(Array(0, 3, 0, 2, 0, 2, -1, -1))),
        ("ababe", Some(Array(0, 5, 0, 4, 3, 4, 2, 3))),
        ("abcdcde", Some(Array(0, 7, 0, 6, 4, 6, 0, 1))),
        ("ababcdcde", Some(Array(0, 9, 0, 8, 6, 8, 2, 3))),
        ("", None),
        ("ab", None),
        ("abc", None)
      )
    )
  }
}
