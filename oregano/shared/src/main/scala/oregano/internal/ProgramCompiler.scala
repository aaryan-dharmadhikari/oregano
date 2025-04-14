package oregano.internal

import cats.collections.Diet

// PLACEHOLDER:
val MAX_RUNE = 0x10FFFF

final case class Frag(
  i: Int,                // Instruction index
  out: Int = 0,          // Patch list
  nullable: Boolean = false
)

def dietToRanges(diet: Diet[Int]): List[Int] = {
  diet.toIterator.foldLeft(List.empty[Int]) { (acc, range) =>
    acc :+ range.start :+ range.end
  }.toList
}

class ProgramCompiler {
  private val prog = Prog()

  // always insert FAIL as first instruction
  prog.addInst(InstOp.FAIL)

  def compileRegexp(re: Pattern): Prog = {
    val f = compile(re)
    prog.patch(f.out, newInst(InstOp.MATCH).i)
    prog.start = f.i
    prog
  }

  private def newInst(op: InstOp): Frag = {
    val pc = prog.addInst(op)
    Frag(pc, 0, nullable = true)
  }

  private def nop(): Frag = {
    val f = newInst(InstOp.NOP)
    Frag(f.i, f.i << 1)
  }

  private def fail(): Frag = Frag(0, 0)

//   private def cap(arg: Int): Frag = {
//     val f = newInst(InstOp.CAPTURE)
//     val inst = prog.getInst(f.i)
//     inst.arg = arg
//     if prog.numCap < arg + 1 then prog.numCap = arg + 1
//     Frag(f.i, f.i << 1)
//   }

  private def cat(f1: Frag, f2: Frag): Frag = {
    if f1.i == 0 || f2.i == 0 then return fail()
    prog.patch(f1.out, f2.i)
    Frag(f1.i, f2.out, f1.nullable && f2.nullable)
  }

  private def alt(f1: Frag, f2: Frag): Frag = {
    if f1.i == 0 then return f2
    if f2.i == 0 then return f1
    val f = newInst(InstOp.ALT)
    val i = prog.getInst(f.i)
    i.out = f1.i
    i.arg = f2.i
    val merged = prog.append(f1.out, f2.out)
    Frag(f.i, merged, f1.nullable || f2.nullable)
  }

  private def loop(f1: Frag, nongreedy: Boolean): Frag = {
    val f = newInst(InstOp.ALT)
    val i = prog.getInst(f.i)
    if nongreedy then
      i.arg = f1.i
      prog.patch(f1.out, f.i)
      Frag(f.i, f.i << 1, nullable = true)
    else
      i.out = f1.i
      prog.patch(f1.out, f.i)
      Frag(f.i, (f.i << 1) | 1, nullable = true)
  }

  private def quest(f1: Frag, nongreedy: Boolean): Frag = {
    val f = newInst(InstOp.ALT)
    val i = prog.getInst(f.i)
    val patchedOut = if nongreedy then
      i.arg = f1.i
      prog.append(f.i << 1, f1.out)
    else
      i.out = f1.i
      prog.append((f.i << 1) | 1, f1.out)
    Frag(f.i, patchedOut, nullable = true)
  }

  private def plus(f1: Frag, nongreedy: Boolean): Frag =
    Frag(f1.i, loop(f1, nongreedy).out, f1.nullable)

  private def star(f1: Frag, nongreedy: Boolean): Frag =
    if f1.nullable then quest(plus(f1, nongreedy), nongreedy)
    else loop(f1, nongreedy)

//   private def empty(op: Int): Frag = {
//     val f = newInst(InstOp.EMPTY_WIDTH)
//     prog.getInst(f.i).arg = op
//     Frag(f.i, f.i << 1)
//   }

  private def rune(r: Int, flags: Int): Frag =
    rune(Array(r), flags)

  private def rune(runes: Array[Int], flags0: Int): Frag = {
    val f = newInst(InstOp.RUNE)
    val inst = prog.getInst(f.i)
    // var flags = flags0 & RE2.FOLD_CASE
    val flags = flags0 
    // if (runes.length != 1 || Unicode.simpleFold(runes(0)) == runes(0))
    //   flags &= ~RE2.FOLD_CASE

    inst.runes = runes
    inst.arg = flags
    // f.nullable = false

    inst.op =
    //   if ((flags & RE2.FOLD_CASE) == 0 && runes.length == 1)
        // InstOp.RUNE1 else 
      if (runes.sameElements(Array(0, '\n' - 1, '\n' + 1, MAX_RUNE)))
        InstOp.RUNE_ANY_NOT_NL
      else if (runes.sameElements(Array(0, MAX_RUNE)))
        InstOp.RUNE_ANY
      else InstOp.RUNE

    Frag(f.i, f.i << 1, false)
  }

//   private val ANY_RUNE_NOT_NL = Array(0, '\n' - 1, '\n' + 1, MAX_RUNE)
//   private val ANY_RUNE = Array(0, MAX_RUNE)

  private def compile(re: Pattern): Frag = re match {
    case Pattern.Lit(c) =>
        rune(c, 0)
    case Pattern.Class(diet) =>
        // val runes = diet.toList.sorted
        val runes = dietToRanges(diet)
        val pairs: Array[Int] = runes.sliding(2, 2).flatMap {
            case List(lo, hi) => Seq(lo, hi)
            case List(single) => Seq(single, single)
            case List(_, _, _, _*) => Seq.empty
            case Nil => Seq.empty
        }.toArray
        rune(pairs, 0)

    case Pattern.Cat(Nil) =>
        nop()

    case Pattern.Cat(patterns) =>
        patterns.map(compile).reduce(cat)

    case Pattern.Alt(left, right) =>
        alt(compile(left), compile(right))

    case Pattern.Rep0(pat) => 
        val f = compile(pat)
        if f.nullable then
          quest(f, false)
        else
          star(f, false)
  }
}
//     case RegexpOp.NO_MATCH      => fail()
//     case RegexpOp.EMPTY_MATCH   => nop()
//     case RegexpOp.LITERAL       =>
//       re.runes.foldLeft(Option.empty[Frag]) { (acc, r) =>
//         val frag = rune(r, re.flags)
//         Some(acc.fold(frag)(cat(_, frag)))
//       }.getOrElse(nop())

//     case RegexpOp.CHAR_CLASS    => rune(re.runes, re.flags)
//     case RegexpOp.ANY_CHAR_NOT_NL => rune(ANY_RUNE_NOT_NL, 0)
//     case RegexpOp.ANY_CHAR        => rune(ANY_RUNE, 0)

//     case RegexpOp.BEGIN_LINE      => empty(Utils.EMPTY_BEGIN_LINE)
//     case RegexpOp.END_LINE        => empty(Utils.EMPTY_END_LINE)
//     case RegexpOp.BEGIN_TEXT      => empty(Utils.EMPTY_BEGIN_TEXT)
//     case RegexpOp.END_TEXT        => empty(Utils.EMPTY_END_TEXT)
//     case RegexpOp.WORD_BOUNDARY   => empty(Utils.EMPTY_WORD_BOUNDARY)
//     case RegexpOp.NO_WORD_BOUNDARY=> empty(Utils.EMPTY_NO_WORD_BOUNDARY)

//     case RegexpOp.CAPTURE =>
//       val bra = cap(re.cap << 1)
//       val sub = compile(re.subs.head)
//       val ket = cap(re.cap << 1 | 1)
//       cat(cat(bra, sub), ket)

    // case RegexpOp.STAR   => star(compile(re.subs.head), (re.flags & RE2.NON_GREEDY) != 0)
//     case RegexpOp.PLUS   => plus(compile(re.subs.head), (re.flags & RE2.NON_GREEDY) != 0)
//     case RegexpOp.QUEST  => quest(compile(re.subs.head), (re.flags & RE2.NON_GREEDY) != 0)

//     case RegexpOp.CONCAT =>
//       re.subs.foldLeft(Option.empty[Frag]) { (acc, r) =>
//         val frag = compile(r)
//         Some(acc.fold(frag)(cat(_, frag)))
//       }.getOrElse(nop())

//     case RegexpOp.ALTERNATE =>
//       re.subs.foldLeft(Option.empty[Frag]) { (acc, r) =>
//         val frag = compile(r)
//         Some(acc.fold(frag)(alt(_, frag)))
//       }.getOrElse(nop())
//   }

object ProgramCompiler {
  def apply(): ProgramCompiler = new ProgramCompiler()

  def compileRegexp(re: Pattern): Prog = {
    val compiler = ProgramCompiler()
    compiler.compileRegexp(re)
  }
}

@main def testProgramCompiler(): Unit = {
  val regex = "a*"
  val pattern = Pattern.compile(regex)
  val frag = ProgramCompiler.compileRegexp(pattern)
  println(frag)
}