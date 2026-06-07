/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import oregano.internal.StagedMatchers.{stagedProg, stagedCPS}

/** Exhaustive differential fuzz of the STAGED Prog hybrid (Backoffs fast-path +
  * recursive fallback, composed via the threaded continuation `k`) against the CPS
  * engine, over EVERY short string. Targets patterns that mix the two mechanisms:
  * Backoffs-loop -> recursive-loop sequences, recursive loops wrapping Backoffs
  * loops, and alternations of both. If the continuation threading composes them
  * incorrectly, a disagreement shows up here.
  */
class StagedHybridCompositionFuzz extends AnyFlatSpec {

  private def allStrings(alphabet: String, maxLen: Int): Seq[String] = {
    val cs = alphabet.map(_.toString)
    def go(n: Int): Seq[String] = if (n == 0) Seq("") else for { p <- go(n - 1); c <- cs } yield p + c
    (0 to maxLen).flatMap(go)
  }

  private def check(label: String, prog: CharSequence => Boolean, cps: CharSequence => Boolean,
                    alphabet: String, maxLen: Int): Unit = {
    var n = 0
    for (s <- allStrings(alphabet, maxLen)) {
      withClue(s"$label on '$s': prog=${prog(s)} cps=${cps(s)}") { prog(s) shouldBe cps(s) }
      n += 1
    }
    info(s"$label: $n strings agreed")
  }

  it should "Backoffs-loop then recursive-loop: (ab)*(a|ab)*c" in
    check("(ab)*(a|ab)*c", stagedProg("(ab)*(a|ab)*c"), stagedCPS("(ab)*(a|ab)*c"), "abc", 6)

  it should "recursive-loop then Backoffs-loop: (a|ab)*(b*)c" in
    check("(a|ab)*(b*)c", stagedProg("(a|ab)*(b*)c"), stagedCPS("(a|ab)*(b*)c"), "abc", 6)

  it should "recursive loop wrapping a Backoffs loop: ((a*)b)*c" in
    check("((a*)b)*c", stagedProg("((a*)b)*c"), stagedCPS("((a*)b)*c"), "abc", 6)

  it should "Backoffs loop then recursive loop with overlap: (a*)(a|ab)*c" in
    check("(a*)(a|ab)*c", stagedProg("(a*)(a|ab)*c"), stagedCPS("(a*)(a|ab)*c"), "abc", 6)

  it should "alternation of a Backoffs loop and a recursive loop: ((ab)*|(a|ba)*)c" in
    check("((ab)*|(a|ba)*)c", stagedProg("((ab)*|(a|ba)*)c"), stagedCPS("((ab)*|(a|ba)*)c"), "abc", 6)

  it should "recursive outer wrapping recursive and Backoffs inner: ((a|aa)*b*)*c" in
    check("((a|aa)*b*)*c", stagedProg("((a|aa)*b*)*c"), stagedCPS("((a|aa)*b*)*c"), "abc", 6)

  it should "Backoffs, literal, recursive in sequence: (ab)*d(a|ab)*e" in
    check("(ab)*d(a|ab)*e", stagedProg("(ab)*d(a|ab)*e"), stagedCPS("(ab)*d(a|ab)*e"), "abde", 5)

  it should "nested recursive with trailing Backoffs: (b*)*(ab)*c" in
    check("(b*)*(ab)*c", stagedProg("(b*)*(ab)*c"), stagedCPS("(b*)*(ab)*c"), "abc", 6)
}
