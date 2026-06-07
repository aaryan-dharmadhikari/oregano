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
import scala.quoted.Quotes
import scala.quoted.staging

/** Runtime-MSP property test. Unlike RegexPropertyTests (which drives the *runtime*
  * engines), this stages the ACTUAL Prog backtracking matcher at runtime via
  * `scala.quoted.staging.run` for each randomly generated regex, then checks the
  * compiled matcher against java.util.regex. It is the only way property-based testing
  * can reach the staged code generator over random regexes.
  *
  * OFF BY DEFAULT — it compiles a matcher per case (slow), and runtime staging is not
  * thread-safe (this is the concurrency pitfall noted in the thesis's future work). It
  * is JVM-only (staging needs the compiler) and lives in oregano/jvm/src/test. Run it
  * alone, single-threaded:
  *
  *   sbt -Doregano.staging=1 'oregano/testOnly oregano.internal.StagedRuntimePBT'
  */
class StagedRuntimePBT extends AnyFlatSpec with ScalaCheckPropertyChecks {

  // small subset generator (kept local so this file stays JVM-only / self-contained)
  private val charG: Gen[Char] = Gen.oneOf('a', 'b', 'c')
  private val litG: Gen[String] = charG.map(_.toString)
  private val classG: Gen[String] =
    Gen.nonEmptyListOf(charG).map(cs => cs.distinct.sorted.mkString("[", "", "]"))
  private def atomG(d: Int): Gen[String] =
    if (d <= 0) Gen.oneOf(litG, classG)
    else Gen.frequency(3 -> litG, 2 -> classG, 2 -> regexG(d - 1).map(r => s"($r)"))
  private def quantG(d: Int): Gen[String] =
    for { a <- atomG(d); q <- Gen.oneOf("", "", "*", "+") } yield a + q
  private def concatG(d: Int): Gen[String] =
    for { n <- Gen.choose(1, 3); ps <- Gen.listOfN(n, quantG(d)) } yield ps.mkString
  private def regexG(d: Int): Gen[String] =
    if (d <= 0) quantG(0)
    else Gen.frequency(3 -> concatG(d), 1 -> (for { l <- concatG(d); r <- concatG(d) } yield s"$l|$r"))
  private val genRegex: Gen[String] = regexG(2).suchThat(s => s.nonEmpty && s.length <= 24)
  private val genInput: Gen[String] =
    for { n <- Gen.choose(0, 6); cs <- Gen.listOfN(n, charG) } yield cs.mkString

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 300) // each case compiles a matcher (slow; opt-in)

  it should "stage the Prog matcher at runtime and agree with java.util.regex" in {
    assume(sys.props.get("oregano.staging").contains("1"),
           "disabled by default — run with -Doregano.staging=1")
    given staging.Compiler = staging.Compiler.make(getClass.getClassLoader)
    forAll(genRegex, genInput) { (re, in) =>
      // Discard (rather than error on) anything outside the matcher-supported subset:
      // if our parser/builder rejects it (PatternBuilder throws on unsupported nodes)
      // or java rejects it, skip the case. Robust to generator drift. staging.run stays
      // OUTSIDE the Try so a genuine staging/matching failure still fails the test.
      val prog = Try {
        val pr = Pattern.compile(re)
        ProgramCompiler.compileRegexp(pr.pattern, pr.groupCount)
      }
      val oracle = Try(java.util.regex.Pattern.matches(re, in))
      whenever(prog.isSuccess && oracle.isSuccess) {
        val matcher: CharSequence => Boolean =
          staging.run { (q: Quotes) ?=> BacktrackingProgMatcher.genMatcher(prog.get)(using q) }
        withClue(s"regex='$re' input='$in'") {
          matcher(in) shouldBe oracle.get
        }
      }
    }
  }
}
