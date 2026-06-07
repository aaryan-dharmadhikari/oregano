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

/** Prog vs CPS vs java.util.regex on COMPLEX, realistic patterns (within the supported
  * subset: literals, classes, `|`, `*`, `+`, `(...)`, `(?:...)`, `.`).
  *
  * Most realistic patterns have variable-width loop bodies (`[a-z]+`, nested groups), which
  * route Prog onto its recursive (== staged CPS) path, so these double as a parity check on
  * non-toy inputs. Two cases are deliberately off that pattern:
  *   - `fixedQuadFail` has a fixed-width (width-4) loop body -> Prog's Backoffs fast-path,
  *     where it should beat CPS.
  *   - `redosFail` is `(?:a+)+b`, the classic nested-loop blowup, to show java.util.regex
  *     going exponential while both Oregano engines stay tractable (n kept at 20).
  *
  * AverageTime / microseconds, lower is better. Correctness vs java is asserted in setup.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
class ComplexPatternBenchmark {

  // ---- patterns (inline val so they are compile-time constants the staging macro can read) ----
  inline val EMAIL = "[a-z0-9]+(?:[._][a-z0-9]+)*@[a-z0-9]+(?:[.][a-z0-9]+)+"
  inline val CSV   = "(?:[a-z]+,)+[a-z]+"
  inline val IP    = "[0-9]+(?:[.][0-9]+)+"
  inline val PATH  = "[a-z]+(?:/[a-z]+)+"
  inline val ALTKW = "(?:foo|bar|baz|qux)+"
  inline val QUAD  = "(?:[0-9][0-9][0-9][0-9])+x"   // fixed-width-4 loop -> Backoffs
  inline val REDOS = "(?:a+)+b"                     // nested loop -> recursive / ReDoS

  // ---- staged matchers ----
  private val pEmail = prog(EMAIL); private val cEmail = cps(EMAIL)
  private val pCsv   = prog(CSV);   private val cCsv   = cps(CSV)
  private val pIp    = prog(IP);    private val cIp    = cps(IP)
  private val pPath  = prog(PATH);  private val cPath  = cps(PATH)
  private val pAlt   = prog(ALTKW); private val cAlt   = cps(ALTKW)
  private val pQuad  = prog(QUAD);  private val cQuad  = cps(QUAD)
  private val pRedos = prog(REDOS); private val cRedos = cps(REDOS)

  // ---- java baselines ----
  private val jEmail = JPattern.compile(EMAIL)
  private val jCsv   = JPattern.compile(CSV)
  private val jIp    = JPattern.compile(IP)
  private val jPath  = JPattern.compile(PATH)
  private val jAlt   = JPattern.compile(ALTKW)
  private val jQuad  = JPattern.compile(QUAD)
  private val jRedos = JPattern.compile(REDOS)

  // ---- inputs ----
  private val R = 100
  val emailIn = "user" + (".abc" * R) + "@" + "mail" + (".net" * R)   // matches
  val csvIn   = ("ab," * R) + "ab"                                    // matches
  val ipIn    = "10" + (".168" * R)                                   // matches
  val pathIn  = "usr" + ("/bin" * R)                                  // matches
  val altIn   = ("foobarbazqux" * R)                                  // matches
  val quadIn  = ("1234" * R)                                          // (?:dddd)+x with no 'x' -> fail (full backoff)
  val redosIn = "a" * 20                                              // (?:a+)+b with no 'b' -> fail (exponential)

  @Setup(Level.Trial)
  def validate(): Unit = {
    def check(re: String, p: CharSequence => Boolean, c: CharSequence => Boolean, j: JPattern, in: String): Unit = {
      val jm = j.matcher(in).matches()
      assert(p(in) == jm, s"prog != java on /$re/: prog=${p(in)} java=$jm")
      assert(c(in) == jm, s"cps  != java on /$re/: cps=${c(in)} java=$jm")
    }
    check(EMAIL, pEmail, cEmail, jEmail, emailIn)
    check(CSV,   pCsv,   cCsv,   jCsv,   csvIn)
    check(IP,    pIp,    cIp,    jIp,    ipIn)
    check(PATH,  pPath,  cPath,  jPath,  pathIn)
    check(ALTKW, pAlt,   cAlt,   jAlt,   altIn)
    check(QUAD,  pQuad,  cQuad,  jQuad,  quadIn)
    check(REDOS, pRedos, cRedos, jRedos, redosIn)
  }

  // email
  @Benchmark def prog_email(): Boolean = pEmail(emailIn)
  @Benchmark def cps_email(): Boolean  = cEmail(emailIn)
  @Benchmark def java_email(): Boolean = jEmail.matcher(emailIn).matches()

  // csv
  @Benchmark def prog_csv(): Boolean = pCsv(csvIn)
  @Benchmark def cps_csv(): Boolean  = cCsv(csvIn)
  @Benchmark def java_csv(): Boolean = jCsv.matcher(csvIn).matches()

  // ip
  @Benchmark def prog_ip(): Boolean = pIp(ipIn)
  @Benchmark def cps_ip(): Boolean  = cIp(ipIn)
  @Benchmark def java_ip(): Boolean = jIp.matcher(ipIn).matches()

  // path
  @Benchmark def prog_path(): Boolean = pPath(pathIn)
  @Benchmark def cps_path(): Boolean  = cPath(pathIn)
  @Benchmark def java_path(): Boolean = jPath.matcher(pathIn).matches()

  // keyword alternation
  @Benchmark def prog_alt(): Boolean = pAlt(altIn)
  @Benchmark def cps_alt(): Boolean  = cAlt(altIn)
  @Benchmark def java_alt(): Boolean = jAlt.matcher(altIn).matches()

  // fixed-width-4 loop (Backoffs regime)
  @Benchmark def prog_quad(): Boolean = pQuad(quadIn)
  @Benchmark def cps_quad(): Boolean  = cQuad(quadIn)
  @Benchmark def java_quad(): Boolean = jQuad.matcher(quadIn).matches()

  // nested-loop ReDoS
  @Benchmark def prog_redos(): Boolean = pRedos(redosIn)
  @Benchmark def cps_redos(): Boolean  = cRedos(redosIn)
  @Benchmark def java_redos(): Boolean = jRedos.matcher(redosIn).matches()
}
