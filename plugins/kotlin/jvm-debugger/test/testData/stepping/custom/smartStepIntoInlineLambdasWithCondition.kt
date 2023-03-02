package smartStepIntoInlineLambdasWithCondition

inline fun foo(a: Boolean, b: Boolean, c: Boolean) {
    1.letIf(a) { it }.letIf(b) { "foo $it" }.letIf(c) { "bar $it" }
}

inline fun <T, R> T.letIf(condition: Boolean, block: (T) -> R?): R? {
    return if (condition) block(this) else null
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
    foo(true, true, false)

    // STEP_INTO: 1
    // SMART_STEP_INTO_BY_INDEX: 2
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 4
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 6
    // RESUME: 1
    //Breakpoint!
    foo(false, false, false)

    // STEP_INTO: 1
    // SMART_STEP_INTO_BY_INDEX: 2
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 4
    // STEP_OUT: 1
    // SMART_STEP_INTO_BY_INDEX: 6
    // RESUME: 1
    //Breakpoint!
    foo(false, true, false)
    foo(false, true, true)
    foo(true, false, true)
    foo(true, true, true)
}
