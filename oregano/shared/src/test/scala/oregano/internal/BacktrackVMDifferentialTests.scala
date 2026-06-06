/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package oregano.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*

/** Differential test: the explicit-stack BacktrackVM must agree with the CPS
  * engine (the correctness ground truth) on EVERY short string, for a battery of
  * patterns spanning simple loops, alternation-in-loop, nesting, and nullable
  * bodies. This is the real check on the empty-iteration guard.
  */
class BacktrackVMDifferentialTests extends AnyFlatSpec {

  private def allStrings(alphabet: String, maxLen: Int): Seq[String] = {
    val chars = alphabet.map(_.toString)
    def go(len: Int): Seq[String] =
      if (len == 0) Seq("")
      else for { p <- go(len - 1); c <- chars } yield p + c
    (0 to maxLen).flatMap(go)
  }

  private def vm(regex: String): CharSequence => Boolean = {
    val pr = Pattern.compile(regex)
    val prog = ProgramCompiler.compileRegexp(pr.pattern, pr.groupCount)
    (in: CharSequence) => BacktrackVM.matches(prog, in)
  }

  private def cps(regex: String): CharSequence => Boolean = {
    val pr = Pattern.compile(regex)
    (in: CharSequence) => CPSMatcher.matches(pr.pattern, pr.groupCount, in)
  }

  // (regex, alphabet, maxLen)
  private val patterns = Seq(
    ("(a|ab)*c",          "abc",   5),
    ("(ab|a)*c",          "abc",   5),
    ("(a|b)*c",           "abc",   5),
    ("(b*)*bc",           "bc",    6),
    ("(a*)*b",            "ab",    6),
    ("((b*)*)*bc",        "bc",    6),
    ("((a*)b*)*bc",       "ab",    6),
    ("((a|aa)*)b",        "ab",    6),
    ("(ab)*c",            "abc",   5),
    ("a*b",               "ab",    6),
    ("(a*)(b*)c",         "abc",   5),
    ("(a|bb)*c",          "abc",   5),
    ("(((a)|b|cd)*)e",    "abcde", 4)
  )

  for ((re, alphabet, maxLen) <- patterns) {
    it should s"agree with CPS on all strings up to length $maxLen for $re" in {
      val v = vm(re)
      val c = cps(re)
      var checked = 0
      for (s <- allStrings(alphabet, maxLen)) {
        withClue(s"$re on '$s': VM=${v(s)} CPS=${c(s)}") { v(s) shouldBe c(s) }
        checked += 1
      }
      info(s"$re: $checked strings agreed")
    }
  }
}
