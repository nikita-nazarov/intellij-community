package nestedInlineFunctions3

inline fun foo(xFoo: Int, f: (Int) -> Unit, g: (Int) -> Unit) {
    bar(0, 1, 2)
    f(1)
    bar(1, 2, 3)
    g(2)
    bar(2, 3, 4)
}

inline fun bar(xBar1: Int, xBar2: Int, xBar3: Int) {
    baz(100, 101, 102)
}

inline fun baz(xBaz1: Int, xBaz2: Int, xBaz3: Int) {
    x1()
    x2()
}

inline fun x1() {
    println()
}

inline fun x2() {
    val x2 = 2
    //Breakpoint!
    println()
}

fun main() {
    foo(1, {
        val y1 = 1
        bar(0, 1, 2)
        //Breakpoint!
        println()
    }, {
        val y2 = 2
        bar(1, 2, 3)
        //Breakpoint!
        println()
    })

     foo(2, {
         val y1 = 1
         bar(2, 3, 4)
         //Breakpoint!
         println()
     }, {
         val y2 = 2
         bar(3, 4, 5)
         //Breakpoint!
         println()
     })
}

// EXPRESSION: x2
// RESULT: 2: I
// EXPRESSION: x2
// RESULT: 2: I
// EXPRESSION: y1 + it
// RESULT: 2: I
// EXPRESSION: x2
// RESULT: 2: I
// EXPRESSION: x2
// RESULT: 2: I
// EXPRESSION: y2 + it
// RESULT: 4: I
// EXPRESSION: x2
// RESULT: 2: I

// EXPRESSION: x2
// RESULT: 2: I
// EXPRESSION: x2
// RESULT: 2: I
// EXPRESSION: y1 + it
// RESULT: 2: I
// EXPRESSION: x2
// RESULT: 2: I
// EXPRESSION: x2
// RESULT: 2: I
// EXPRESSION: y2 + it
// RESULT: 4: I
// EXPRESSION: x2
// RESULT: 2: I

// PRINT_FRAME
// SHOW_KOTLIN_VARIABLES
