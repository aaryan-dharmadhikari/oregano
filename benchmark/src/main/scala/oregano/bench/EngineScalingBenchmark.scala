/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package oregano.bench

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit
import java.util.regex.{Pattern as JPattern}
import oregano.internal.StagedEngines.{prog, cps}

/** How the staged Prog matcher, the legacy staged CPS matcher, and java.util.regex scale
  * with INPUT LENGTH, in the Backoffs regime (fixed-width unambiguous loop bodies) and an
  * ambiguous control.
  *
  * All sizes are kept below CPS's stack-overflow ceiling (~131k on a single fixed-width
  * loop; see EngineStackRobustnessTests) so every engine actually completes. Within that
  * window every engine is linear in n, so the interesting quantity is the CONSTANT: the
  * prog/cps ratio is expected to stay ~stable across sizes in the Backoffs regime
  * (Prog's arithmetic backtrack vs CPS's per-iteration walk) and ~1 in the ambiguous
  * control (where Prog drops to its recursive path == staged CPS, Theorem 2).
  *
  * Pair with `-prof gc`: CPS allocates a closure/continuation per iteration, Prog does
  * not, which is the mechanism behind the constant-factor gap.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
class EngineScalingBenchmark {

  @Param(Array("256", "1024", "4096", "16384"))
  var n: Int = scala.compiletime.uninitialized

  // ---- staged matchers (fixed patterns; literal at the splice site) ----
  private val pClass = prog("[a-z]*#"); private val cClass = cps("[a-z]*#")
  private val pLit   = prog("(abc)*d"); private val cLit   = cps("(abc)*d")
  private val pAlt   = prog("(a|b)*#"); private val cAlt   = cps("(a|b)*#")

  // ---- java.util.regex baselines (anchored full match) ----
  private val jClass = JPattern.compile("[a-z]*#")
  private val jLit   = JPattern.compile("(abc)*d")
  private val jAlt   = JPattern.compile("(a|b)*#")

  // ---- inputs (all FAIL at the trailing literal, forcing a full backtrack of the loop) ----
  private var classIn: String = ""
  private var litIn: String   = ""
  private var altIn: String   = ""

  @Setup(Level.Trial)
  def setup(): Unit = {
    classIn = "a" * n
    litIn   = "abc" * math.max(1, n / 3)
    altIn   = "a" * n
  }

  // ---- Backoffs regime: [a-z]*# ----
  @Benchmark def prog_class(): Boolean = pClass(classIn)
  @Benchmark def cps_class(): Boolean  = cClass(classIn)
  @Benchmark def java_class(): Boolean = jClass.matcher(classIn).matches()

  // ---- Backoffs regime: (abc)*d ----
  @Benchmark def prog_lit(): Boolean = pLit(litIn)
  @Benchmark def cps_lit(): Boolean  = cLit(litIn)
  @Benchmark def java_lit(): Boolean = jLit.matcher(litIn).matches()

  // ---- Ambiguous control: (a|b)*# (Prog recursive path == CPS) ----
  @Benchmark def prog_alt(): Boolean = pAlt(altIn)
  @Benchmark def cps_alt(): Boolean  = cAlt(altIn)
  @Benchmark def java_alt(): Boolean = jAlt.matcher(altIn).matches()
}
