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
}
