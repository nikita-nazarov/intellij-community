package stepOutFromInlineLambdaOnOneLine

fun main() {
    val x = 1
    //Breakpoint! (lambdaOrdinal = 1)
    x.let { it + 1 }.let { it + 2 }
}

// STEP_OUT: 1
