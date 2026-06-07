/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package oregano.internal

import scala.quoted.*

/** Benchmark-only staged entry points exposing BOTH backtracking engines for the same
  * regex, so JMH can compare the production staged Prog matcher against the legacy CPS
  * matcher head-to-head on identical patterns and inputs.
  *
  * The `.regex` macro itself now only ever stages Prog (CPS is retained solely as a
  * differential-test oracle), so this is the only place the two engines can be driven
  * side by side. It lives in `package oregano.internal` purely to reach the two
  * (package-private) matcher objects; it is not part of the published library.
  */
object StagedEngines:
  inline def prog(inline regex: String): CharSequence => Boolean = ${ progImpl('regex) }
  inline def cps(inline regex: String): CharSequence => Boolean = ${ cpsImpl('regex) }

  private def progImpl(regexExpr: Expr[String])(using Quotes): Expr[CharSequence => Boolean] =
    val regex = regexExpr.valueOrAbort
    val PatternResult(pattern, groupCount, _) = Pattern.compile(regex)
    val prog = ProgramCompiler.compileRegexp(pattern, groupCount)
    BacktrackingProgMatcher.genMatcher(prog)

  private def cpsImpl(regexExpr: Expr[String])(using Quotes): Expr[CharSequence => Boolean] =
    val regex = regexExpr.valueOrAbort
    val PatternResult(pattern, _, _) = Pattern.compile(regex)
    CPSMatcher.genMatcherPattern(pattern)
