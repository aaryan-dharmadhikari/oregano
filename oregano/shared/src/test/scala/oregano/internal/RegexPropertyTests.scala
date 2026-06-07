/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package oregano.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalacheck.Gen
import scala.util.Try

/** Property-based differential test against an independent oracle (java.util.regex).
  *
  * The staged matchers need literal regexes at compile time, so we can't generate
  * regexes for them — but the runtime engines (BacktrackVM, the runtime CPS matcher)
  * share the same Prog IR and backtracking semantics, and full-match membership is
  * unambiguous for the regular subset, so they must agree with Java. scalacheck
  * generates random regexes (over a small supported subset) and inputs, and shrinks
  * any disagreement to a minimal counterexample.
  */
class RegexPropertyTests extends AnyFlatSpec with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 2000)

  // ---- generators: a subset both our parser and java.util.regex agree on ----
  private val charG: Gen[Char] = Gen.oneOf('a', 'b', 'c')
  private val litG: Gen[String] = charG.map(_.toString)
  private val classG: Gen[String] =
    Gen.nonEmptyListOf(charG).map(cs => cs.distinct.sorted.mkString("[", "", "]"))

  private def atomG(d: Int): Gen[String] =
    if (d <= 0) Gen.oneOf(litG, classG)
    else Gen.frequency(
      3 -> litG,
      2 -> classG,
      2 -> regexG(d - 1).map(r => s"($r)"),
      1 -> regexG(d - 1).map(r => s"(?:$r)")
    )

  private def quantG(d: Int): Gen[String] =
    for { a <- atomG(d); q <- Gen.oneOf("", "", "", "*", "+") } yield a + q

  private def concatG(d: Int): Gen[String] =
    for { n <- Gen.choose(1, 3); ps <- Gen.listOfN(n, quantG(d)) } yield ps.mkString

  private def regexG(d: Int): Gen[String] =
    if (d <= 0) quantG(0)
    else Gen.frequency(
      3 -> concatG(d),
      1 -> (for { l <- concatG(d); r <- concatG(d) } yield s"$l|$r")
    )

  private val genRegex: Gen[String] = regexG(2).suchThat(s => s.nonEmpty && s.length <= 30)
  private val genInput: Gen[String] =
    for { n <- Gen.choose(0, 6); cs <- Gen.listOfN(n, charG) } yield cs.mkString

  // ---- capture-safe generator (for the capture property) ----
  // Within this subset the ONLY place Oregano's captures diverge from java is java's
  // capture-leak-on-backtrack wart (docs/capture-semantics.md), which fires *only* when a
  // quantifier (*/+) backtracks past an iteration that captured — failed ALTERNATION branches
  // roll back captures in both engines. We don't replicate the wart, so this generator allows
  // captures everywhere EXCEPT inside a quantified subexpression. Two mutually-recursive
  // families: `cf*` is capture-FREE (the only thing a quantifier may wrap); `cs*` is
  // capture-SAFE (captures allowed, but only ever unquantified).
  private def cfAtomG(d: Int): Gen[String] =
    if (d <= 0) Gen.oneOf(litG, classG)
    else Gen.frequency(3 -> litG, 2 -> classG, 2 -> cfRegexG(d - 1).map(r => s"(?:$r)"))
  private def cfQuantG(d: Int): Gen[String] =
    for { a <- cfAtomG(d); q <- Gen.oneOf("", "", "*", "+") } yield a + q
  private def cfConcatG(d: Int): Gen[String] =
    for { n <- Gen.choose(1, 3); ps <- Gen.listOfN(n, cfQuantG(d)) } yield ps.mkString
  private def cfRegexG(d: Int): Gen[String] =
    if (d <= 0) cfQuantG(0)
    else Gen.frequency(3 -> cfConcatG(d), 1 -> (for { l <- cfConcatG(d); r <- cfConcatG(d) } yield s"$l|$r"))

  private def csAtomG(d: Int): Gen[String] =
    if (d <= 0) Gen.oneOf(litG, classG)
    else Gen.frequency(
      3 -> litG,
      2 -> classG,
      2 -> csRegexG(d - 1).map(r => s"($r)"),
      1 -> csRegexG(d - 1).map(r => s"(?:$r)")
    )
  private def csQuantG(d: Int): Gen[String] =
    Gen.frequency(
      3 -> csAtomG(d),                                                    // unquantified: captures allowed
      1 -> (for { a <- cfAtomG(d); q <- Gen.oneOf("*", "+") } yield a + q) // quantified: capture-free body only
    )
  private def csConcatG(d: Int): Gen[String] =
    for { n <- Gen.choose(1, 3); ps <- Gen.listOfN(n, csQuantG(d)) } yield ps.mkString
  private def csRegexG(d: Int): Gen[String] =
    if (d <= 0) csQuantG(0)
    else Gen.frequency(3 -> csConcatG(d), 1 -> (for { l <- csConcatG(d); r <- csConcatG(d) } yield s"$l|$r"))

  private val genCaptureSafeRegex: Gen[String] =
    csRegexG(2).suchThat(s => s.nonEmpty && s.length <= 30)

  it should "dump a sample of generated (regex, input) cases with all three verdicts" in {
    println("%-26s %-10s %5s %5s %5s".format("regex", "input", "vm", "cps", "java"))
    var shown = 0
    while (shown < 40) {
      (for { re <- genRegex.sample; in <- genInput.sample } yield (re, in)).foreach { (re, in) =>
        val verdicts = Try {
          val pr = Pattern.compile(re)
          val prog = ProgramCompiler.compileRegexp(pr.pattern, pr.groupCount)
          val vm = BacktrackVM.matches(prog, in)
          val cps = CPSMatcher.matches(pr.pattern, pr.groupCount, in)
          val jm = java.util.regex.Pattern.matches(re, in)
          (vm, cps, jm)
        }
        verdicts.foreach { (vm, cps, j) =>
          println("%-26s %-10s %5s %5s %5s".format(re, s"'$in'", vm, cps, j))
          shown += 1
        }
      }
    }
    succeed
  }

  it should "agree with java.util.regex on full match (BacktrackVM and runtime CPS)" in {
    forAll(genRegex, genInput) { (re, in) =>
      val ours = Try {
        val pr = Pattern.compile(re)
        val prog = ProgramCompiler.compileRegexp(pr.pattern, pr.groupCount)
        (BacktrackVM.matches(prog, in), CPSMatcher.matches(pr.pattern, pr.groupCount, in))
      }
      val oracle = Try(java.util.regex.Pattern.matches(re, in))
      whenever(ours.isSuccess && oracle.isSuccess) {
        val (vm, cps) = ours.get
        withClue(s"regex='$re' input='$in'  vm=$vm cps=$cps java=${oracle.get}") {
          vm shouldBe oracle.get
          cps shouldBe oracle.get
        }
      }
    }
  }

  /** Java's captured *text* per group (group 0 = whole match), or None if no full match.
    * `m.group(i)` is null for an unmatched group, which Option lifts to None. */
  private def javaGroups(re: String, in: String): Option[List[Option[String]]] = {
    val m = java.util.regex.Pattern.compile(re).matcher(in)
    if (m.matches()) Some((0 to m.groupCount).map(i => Option(m.group(i))).toList)
    else None
  }

  /** The same captured text from Oregano's [start_i, end_i, ...] slot array. */
  private def ourGroups(arr: Array[Int], in: CharSequence): List[Option[String]] =
    (0 until arr.length / 2).map { i =>
      val s = arr(2 * i); val e = arr(2 * i + 1)
      if (s < 0 || e < 0) None else Some(in.subSequence(s, e).toString)
    }.toList

  /** The boolean property above is structurally blind to capture-group divergences: two
    * engines can agree a string matches (and on group 0) while disagreeing on where an inner
    * group landed. So we property-check captures against java too.
    *
    * We compare captured *text* (`m.group(i)`), not raw offsets, on purpose. This is the
    * meaningful, user-facing invariant — and it draws a principled line between two distinct
    * java behaviours the offset view conflates:
    *   - Empty-iteration semantics of nested `*`/`+` (java keeps the final empty iteration's
    *     group writes; see CPSMatcher.Rep0): the text genuinely differs (e.g. "" vs "a"), so
    *     this still catches it — Oregano matches java here.
    *   - The java/PCRE capture-LEAK-on-backtrack wart (a group inside a quantifier retains its
    *     value from an iteration that was later abandoned, e.g. `((a))+(a)` on "aa" gives java
    *     g2=[1,2] but Oregano the cleaner g2=[0,1], as RE2/Rust/Go also do): the leaked offset
    *     points at *the same character matched by the same subpattern*, so the TEXT coincides.
    *     We deliberately tolerate that offset-only divergence rather than replicate the wart.
    *
    * Only the runtime CPS matcher produces captures over a generated (non-literal) regex —
    * the staged engines need a literal. */
  it should "agree with java.util.regex on captured group text (runtime CPS)" in {
    forAll(genCaptureSafeRegex, genInput) { (re, in) =>
      val ours = Try {
        val pr = Pattern.compile(re)
        CPSMatcher.matchesWithCaps(pr.pattern, pr.groupCount, in).map(arr => ourGroups(arr, in))
      }
      val oracle = Try(javaGroups(re, in))
      whenever(ours.isSuccess && oracle.isSuccess) {
        withClue(s"regex='$re' input='$in'  cps=${ours.get} java=${oracle.get}") {
          ours.get shouldBe oracle.get
        }
      }
    }
  }
}
