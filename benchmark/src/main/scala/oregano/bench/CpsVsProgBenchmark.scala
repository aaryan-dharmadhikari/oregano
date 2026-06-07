/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package oregano.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import oregano.internal.StagedEngines.{prog, cps}

/** Head-to-head: the production staged Prog matcher vs the legacy staged CPS matcher,
  * on identical patterns/inputs. Two regimes (see docs/backoffs-completeness.md):
  *
  *  - Backoffs regime (fixed-width, unambiguous loop body, no alternation): Prog
  *    backtracks by ARITHMETIC (finalPos - i*width); CPS must walk the continuation
  *    position by position. Prog should win, increasingly so as the backtrack depth
  *    grows. The `*Fail*` cases force a full backtrack from the end to 0.
  *  - Ambiguous regime (alternation / nested loop in the body): Prog drops to its
  *    recursive continuation path, which IS staged CPS (Theorem 2), so the two should
  *    be on par. This is the control: it shows Prog is never WORSE, not that it always
  *    wins.
  *
  * AverageTime / microseconds: lower is better, so a taller CPS bar = CPS is worse.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
class CpsVsProgBenchmark {

  // ---- inputs ----
  // Backoffs regime
  val classMatch   = "a" * 2000               // [a-z]*       full match (forward scan)
  val classFail    = "a" * 2000               // [a-z]*#      no '#'  -> backoff end..0
  val litLoopFail  = "abc" * 600              // (abc)*d      no 'd'  -> backoff by width 3
  val w2Fail       = "a" * 2000               // ([a-z][a-z])*#  width-2 backoff
  // Ambiguous regime (recursive path == CPS)
  val ambigFail    = "a" * 22                 // (a|aa)*b     no 'b'  -> exponential, both engines
  val altWidthFail = "ab" * 11                // (a|ab)*c     no 'c'  -> recursive, both engines

  // ---- staged matchers (one Prog + one CPS per pattern) ----
  val pClassMatch  = prog("[a-z]*");          val cClassMatch  = cps("[a-z]*")
  val pClassFail   = prog("[a-z]*#");         val cClassFail   = cps("[a-z]*#")
  val pLitLoopFail = prog("(abc)*d");         val cLitLoopFail = cps("(abc)*d")
  val pW2Fail      = prog("([a-z][a-z])*#");  val cW2Fail      = cps("([a-z][a-z])*#")
  val pAmbigFail   = prog("(a|aa)*b");        val cAmbigFail   = cps("(a|aa)*b")
  val pAltWidth    = prog("(a|ab)*c");        val cAltWidth    = cps("(a|ab)*c")

  @Setup(Level.Trial)
  def validate(): Unit = {
    def check(re: String, p: CharSequence => Boolean, c: CharSequence => Boolean, in: String): Unit = {
      val j = java.util.regex.Pattern.matches(re, in)
      assert(p(in) == j, s"prog disagrees with java on /$re/: prog=${p(in)} java=$j")
      assert(c(in) == j, s"cps disagrees with java on /$re/: cps=${c(in)} java=$j")
    }
    check("[a-z]*",          pClassMatch,  cClassMatch,  classMatch)
    check("[a-z]*#",         pClassFail,   cClassFail,   classFail)
    check("(abc)*d",         pLitLoopFail, cLitLoopFail, litLoopFail)
    check("([a-z][a-z])*#",  pW2Fail,      cW2Fail,      w2Fail)
    check("(a|aa)*b",        pAmbigFail,   cAmbigFail,   ambigFail)
    check("(a|ab)*c",        pAltWidth,    cAltWidth,    altWidthFail)
  }

  // ---- Backoffs regime: expect Prog << CPS ----
  @Benchmark def prog_classMatch(): Boolean  = pClassMatch(classMatch)
  @Benchmark def cps_classMatch(): Boolean   = cClassMatch(classMatch)

  @Benchmark def prog_classFail(): Boolean   = pClassFail(classFail)
  @Benchmark def cps_classFail(): Boolean    = cClassFail(classFail)

  @Benchmark def prog_litLoopFail(): Boolean = pLitLoopFail(litLoopFail)
  @Benchmark def cps_litLoopFail(): Boolean  = cLitLoopFail(litLoopFail)

  @Benchmark def prog_w2Fail(): Boolean      = pW2Fail(w2Fail)
  @Benchmark def cps_w2Fail(): Boolean       = cW2Fail(w2Fail)

  // ---- Ambiguous regime: expect Prog ~= CPS (control) ----
  @Benchmark def prog_ambigFail(): Boolean   = pAmbigFail(ambigFail)
  @Benchmark def cps_ambigFail(): Boolean    = cAmbigFail(ambigFail)

  @Benchmark def prog_altWidthFail(): Boolean = pAltWidth(altWidthFail)
  @Benchmark def cps_altWidthFail(): Boolean  = cAltWidth(altWidthFail)
}
