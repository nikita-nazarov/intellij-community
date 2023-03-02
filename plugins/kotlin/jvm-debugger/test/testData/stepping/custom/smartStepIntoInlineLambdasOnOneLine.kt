package smartStepIntoInlineLambdasOnOneLine

inline fun foo() {
    1.let { it }.let { it + 1 }.let { it + 2 }
}

fun main() {
    // STEP_INTO: 1
    // SMART_STEP_INTO_BY_INDEX: 2
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 4
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 6
    // RESUME: 1
    //Breakpoint!
    foo()
    // STEP_INTO: 1
    // SMART_STEP_INTO_BY_INDEX: 2
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 4
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 6
    // RESUME: 1
    //Breakpoint!
    foo()
    // STEP_INTO: 1
    // SMART_STEP_INTO_BY_INDEX: 2
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 4
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 6
    // RESUME: 1
    //Breakpoint!
    foo()
}
