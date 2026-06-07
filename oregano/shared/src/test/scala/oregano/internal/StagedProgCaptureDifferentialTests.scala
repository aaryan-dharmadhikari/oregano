/*
 * Copyright 2024 Oregano Contributors <https://github.com/j-mie6/oregano/graphs/contributors>
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package oregano.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers.*
import oregano.internal.StagedMatchers.stagedProgWithCaps

/** Capture-group differential test for the staged Prog matcher.
  *
  * `java.util.regex` is the oracle: its `Matcher.start(i)/end(i)` is exactly the
  * `[start_i, end_i]` slot layout `stagedProgWithCaps` produces (group 0 = whole match,
  * `-1` for an unmatched group), and `Matcher.matches()` is the same whole-match anchoring
  * the staged engine uses. So for every short input we can compare Oregano's
  * `Option[Array[Int]]` to Java's groups exactly — no hand-computed expected arrays.
  *
  * This is the real check on the trickier capture semantics: greedy precedence under
  * repetition, the write-before-match / last-iteration-wins rule for groups inside `*`/`+`,
  * unmatched alternation branches, captures sitting on the Backoffs fast-path, and nullable
  * loop bodies (the classic empty-iteration edge cases).
  */
class StagedProgCaptureDifferentialTests extends AnyFlatSpec {

  private def allStrings(alphabet: String, maxLen: Int): Seq[String] = {
    val chars = alphabet.map(_.toString)
    def go(len: Int): Seq[String] =
      if (len == 0) Seq("")
      else for { p <- go(len - 1); c <- chars } yield p + c
    (0 to maxLen).flatMap(go)
  }

  /** Java's whole-match capture slots, in Oregano's layout, or None if no full match. */
  private def javaCaps(re: String, in: String): Option[List[Int]] = {
    val m = java.util.regex.Pattern.compile(re).matcher(in)
    if (m.matches()) Some((0 to m.groupCount).flatMap(i => List(m.start(i), m.end(i))).toList)
    else None
  }

  private def diff(re: String, m: CharSequence => Option[Array[Int]], alphabet: String, maxLen: Int): Unit = {
    val mismatches = scala.collection.mutable.ListBuffer.empty[String]
    var checked = 0
    for (s <- allStrings(alphabet, maxLen)) {
      val expected = javaCaps(re, s)
      val actual = m(s).map(_.toList)
      if (actual != expected) mismatches += s"'$s': oregano=$actual java=$expected"
      checked += 1
    }
    withClue(s"$re: ${mismatches.size}/$checked inputs disagreed with java.util.regex:\n  ${mismatches.take(12).mkString("\n  ")}\n") {
      mismatches shouldBe empty
    }
    info(s"$re: $checked inputs agreed with java.util.regex (capture groups)")
  }

  // ── sequential / adjacent groups ──
  it should "agree on (a)(b)"        in diff("(a)(b)",       stagedProgWithCaps("(a)(b)"),       "ab",  4)
  it should "agree on (a)(b)(c)"     in diff("(a)(b)(c)",    stagedProgWithCaps("(a)(b)(c)"),    "abc", 4)
  it should "agree on (a*)(b*)"      in diff("(a*)(b*)",     stagedProgWithCaps("(a*)(b*)"),     "ab",  6)
  it should "agree on (a*)(b*)c"     in diff("(a*)(b*)c",    stagedProgWithCaps("(a*)(b*)c"),    "abc", 5)
  it should "agree on (a+)(b+)"      in diff("(a+)(b+)",     stagedProgWithCaps("(a+)(b+)"),     "ab",  6)

  // ── groups under repetition (last-iteration-wins) ──
  it should "agree on (ab)*"         in diff("(ab)*",        stagedProgWithCaps("(ab)*"),        "ab",  6)
  it should "agree on (ab)+"         in diff("(ab)+",        stagedProgWithCaps("(ab)+"),        "ab",  6)
  it should "agree on (abc)*d"       in diff("(abc)*d",      stagedProgWithCaps("(abc)*d"),      "abcd", 5)
  it should "agree on (a(b)c)*"      in diff("(a(b)c)*",     stagedProgWithCaps("(a(b)c)*"),     "abc", 6)
  it should "agree on ((a)|(b))*"    in diff("((a)|(b))*",   stagedProgWithCaps("((a)|(b))*"),   "ab",  5)

  // ── nested groups ──
  it should "agree on ((a)b)"        in diff("((a)b)",       stagedProgWithCaps("((a)b)"),       "ab",  4)
  it should "agree on ((a)(b))"      in diff("((a)(b))",     stagedProgWithCaps("((a)(b))"),     "ab",  4)
  it should "agree on ((ab)*c)"      in diff("((ab)*c)",     stagedProgWithCaps("((ab)*c)"),     "abc", 5)
  it should "agree on ((a*)b*)c"     in diff("((a*)b*)c",    stagedProgWithCaps("((a*)b*)c"),    "abc", 5)

  // ── alternation with unmatched branches ──
  it should "agree on (a)|(b)"       in diff("(a)|(b)",      stagedProgWithCaps("(a)|(b)"),      "ab",  3)
  it should "agree on (ab)|(cd)"     in diff("(ab)|(cd)",    stagedProgWithCaps("(ab)|(cd)"),    "abcd", 4)
  it should "agree on (a)(b)|(c)(d)" in diff("(a)(b)|(c)(d)", stagedProgWithCaps("(a)(b)|(c)(d)"), "abcd", 4)

  // ── captures on the Backoffs fast-path (fixed-width body, capture inside) ──
  it should "agree on ([0-9])*x"     in diff("([0-9])*x",    stagedProgWithCaps("([0-9])*x"),    "01x", 5)
  it should "agree on (.)*"          in diff("(.)*",         stagedProgWithCaps("(.)*"),         "ab",  5)

  // ── ambiguous bodies (recursive path) ──
  it should "agree on (a|ab)*c"      in diff("(a|ab)*c",     stagedProgWithCaps("(a|ab)*c"),     "abc", 5)
  it should "agree on (a|aa)*b"      in diff("(a|aa)*b",     stagedProgWithCaps("(a|aa)*b"),     "ab",  6)
  it should "agree on (((a)|b|cd)*)e" in diff("(((a)|b|cd)*)e", stagedProgWithCaps("(((a)|b|cd)*)e"), "abcde", 4)
  it should "agree on ((a*)b*)bc|(def)" in diff("((a*)b*)bc|(def)", stagedProgWithCaps("((a*)b*)bc|(def)"), "abcdef", 5)

  // ── nullable loop bodies (classic empty-iteration edge cases) ──
  it should "agree on (a*)*b"        in diff("(a*)*b",       stagedProgWithCaps("(a*)*b"),       "ab",  6)
  it should "agree on (b*)*bc"       in diff("(b*)*bc",      stagedProgWithCaps("(b*)*bc"),      "bc",  6)
}
