package oregano.shared

import oregano.regex 

object Main {
  def main(args: Array[String]): Unit = {
    inline val regEx = "abc|def|ghi"
    println("Current inlined regex: " + regEx)
    val compileTime = regEx.regex
    println(s"matches \"abc\": ${compileTime.matches("abc")}")
    println(s"matches \"def\": ${compileTime.matches("def")}")
    println(s"matches \"ghi\": ${compileTime.matches("ghi")}")
    val uninlined = "abc|def"
    println("Current uninlined regex: " + uninlined)
    val runtime = uninlined.regex
    println(s"matches \"abc\": ${runtime.matches("abc")}")
    println(s"matches \"def\": ${runtime.matches("def")}")
    println(s"matches \"ghi\": ${runtime.matches("ghi")}")
  }
}