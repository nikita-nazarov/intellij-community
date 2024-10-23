// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.debugger.core

import com.sun.jdi.Method
import kexter.*
import org.jetbrains.kotlin.idea.debugger.base.util.safeLocation
import org.jetbrains.kotlin.idea.debugger.base.util.safeMethod
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.KotlinMethodSmartStepTarget
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.SmartStepIntoContext
import java.io.File

class DexBytecodeInspectorImpl : DexBytecodeInspector {
    override fun hasOnlyInvokeStatic(method: Method): Boolean {
        val instructions = DexBytecode.fromBytes(method.bytecodes()).instructions
        if (instructions.isEmpty() || instructions.size > 3) {
            return false
        }

        return instructions.first().opcode.isInvokeStatic() &&
                instructions.last().opcode.isReturn() &&
                (instructions.size == 2 || instructions[1].opcode.isMoveResult())
    }

    override fun filterAlreadyExecutedTargets(
        targets: List<KotlinMethodSmartStepTarget>,
        context: SmartStepIntoContext
    ): List<KotlinMethodSmartStepTarget> {
        val (expression, debugProcess, _, lines) = context
        val location = debugProcess.suspendManager.pausedContext?.frameProxy?.safeLocation()
            ?: return targets
        val method = location.safeMethod() ?: return targets
        val bytecodes = method.bytecodes()
        val allLocations = method.allLineLocations()


        val info = DexMethodDebugInfo(allLocations.map { LineTableEntry(it.codeIndex().toUInt(), it.lineNumber()) })
        val bytecode = DexBytecode.fromBytes(bytecodes, info)
        // Lines from SmartStepIntoContext are zero based, dex debug info line numbers start from 1
        val instructions = (lines.start + 1..lines.endInclusive + 1).flatMap { bytecode.instructionsForLineNumber(it) }
        //val file = File("/tmp/locations1.txt")
        //file.appendText("Bytes num: ${bytecodes.size}\n")
        //file.appendText("Instructions num: ${DexBytecode.fromBytes(bytecodes).instructions.size}\n")
        //file.appendText(
        //    allLocations.mapIndexed { i, loc -> "location: ${i} code index: ${loc.codeIndex()}" }.joinToString("\n")
        //)
        return targets
    }
}

private fun Opcode.isInvokeStatic(): Boolean {
    return this == Opcode.INVOKE_STATIC || this == Opcode.INVOKE_STATIC_RANGE
}

private fun Opcode.isMoveResult(): Boolean {
    return this == Opcode.MOVE_RESULT ||
            this == Opcode.MOVE_RESULT_WIDE ||
            this == Opcode.MOVE_RESULT_OBJECT
}

private fun Opcode.isReturn(): Boolean {
    return this == Opcode.RETURN_VOID  ||
            this == Opcode.RETURN_OBJECT  ||
            this == Opcode.RETURN_WIDE  ||
            this == Opcode.RETURN
}
