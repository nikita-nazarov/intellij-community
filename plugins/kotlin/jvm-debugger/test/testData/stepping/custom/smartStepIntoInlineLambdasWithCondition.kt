// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package smartStepIntoInlineLambdasWithCondition

fun foo1() = 1
fun foo2() = 2
fun foo3() = 3

inline fun foo(a: Boolean, b: Boolean, c: Boolean) {
    1.letIf(a) { foo1() }.letIf(b) { foo2() }.letIf(c) { foo3() }
}

inline fun <T, R> T.letIf(condition: Boolean, block: (T) -> R?): R? {
    return if (condition) block(this) else null
}

fun main() {
    // STEP_INTO: 1
    // SMART_STEP_INTO_BY_INDEX: 2
    // STEP_OUT: 2
    // SMART_STEP_INTO_BY_INDEX: 4
    // STEP_OUT: 2
    // SMART_STEP_INTO_BY_INDEX: 6
    // RESUME: 1
    //Breakpoint!
    foo(true, true, false)

    // STEP_INTO: 1
    // SMART_STEP_INTO_BY_INDEX: 2
    // STEP_OUT: 2
    // SMART_STEP_INTO_BY_INDEX: 4
    // STEP_OUT: 2
    // SMART_STEP_INTO_BY_INDEX: 6
    // RESUME: 1
    //Breakpoint!
    foo(false, false, false)

    // STEP_INTO: 1
    // SMART_STEP_INTO_BY_INDEX: 2
    // STEP_OUT: 2
    // SMART_STEP_INTO_BY_INDEX: 4
    // STEP_OUT: 2
    // SMART_STEP_INTO_BY_INDEX: 6
    // RESUME: 1
    //Breakpoint!
    foo(false, true, false)
    foo(false, true, true)
    foo(true, false, true)
    foo(true, true, true)
}
