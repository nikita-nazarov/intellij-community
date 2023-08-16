package standardLibraryFunctions

fun main() {
    val list = listOf(1, 2, 3)
    list
        //Breakpoint! (lambdaOrdinal = 1)
        .map { it / 2 }
        //Breakpoint! (lambdaOrdinal = 1)
        .filter { it > 1 }
}

// EXPRESSION: it
// RESULT: 1: I
// EXPRESSION: it
// RESULT: 2: I
// EXPRESSION: it
// RESULT: 3: I

// EXPRESSION: it
// RESULT: 0: I
// EXPRESSION: it
// RESULT: 1: I
// EXPRESSION: it
// RESULT: 1: I

// PRINT_FRAME
// SHOW_KOTLIN_VARIABLES
