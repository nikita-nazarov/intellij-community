// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.debugger.core

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.PositionManagerAsync
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.psi.util.parentOfType
import com.sun.jdi.Location
import com.sun.jdi.Method
import kexter.*
import org.jetbrains.kotlin.idea.debugger.base.util.safeGetSourcePositionAsync
import org.jetbrains.kotlin.idea.debugger.base.util.safeLocation
import org.jetbrains.kotlin.idea.debugger.base.util.safeMethod
import org.jetbrains.kotlin.idea.debugger.core.stepping.DexFinder
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.KotlinMethodSmartStepTarget
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.KotlinSmartStepTargetFilterer
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.SmartStepIntoContext
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.util.LinkedList

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

    // TODO: remove `runBlockingCancellable` invocations once `filterAlreadyExecutedTargets` becomes `suspend`
    override fun filterAlreadyExecutedTargets(
        targets: List<KotlinMethodSmartStepTarget>,
        context: SmartStepIntoContext
    ): List<KotlinMethodSmartStepTarget> {
        val (expression, debugProcess, _, _) = context
        val location = debugProcess.suspendManager.pausedContext?.frameProxy?.safeLocation()
            ?: return targets
        val method = location.safeMethod() ?: return targets
        val apkFiles = runBlockingCancellable { DexFinder.findApksBySourceElement(debugProcess, expression) }
        if (apkFiles.isEmpty()) {
            return targets
        }
        val dex = DexFinder.findDexWithLocation(apkFiles, location) ?: return targets

        val filterer = KotlinSmartStepTargetFilterer(targets, debugProcess)
        runBlockingCancellable {
            filterer.visitMethodUntliLocation(debugProcess, method, location, dex)
        }
        return filterer.getUnvisitedTargets()
    }

}

private suspend fun KotlinSmartStepTargetFilterer.visitMethodUntliLocation(
    debugProcess: DebugProcessImpl,
    method: Method,
    location: Location,
    dex: Dex
) {
    val debugInfo = method.getDebugInfo()
    if (debugInfo.lineTable.isEmpty()) {
        return
    }

    val methodBytecode = DexBytecode.fromBytes(method.bytecodes(), debugInfo)
    if (methodBytecode.instructions.isEmpty()) {
        return
    }

    val lineTableIterator = debugInfo.lineTable.iterator()
    var nextLine = lineTableIterator.next()
    var currentLineNumber: Int? = null
    var lineEverMatched = false
    var inInline = false
    val inlineCalls = LinkedList(extractInlineCalls(location))
    for (insn in methodBytecode.instructions) {
        if (insn.index >= location.codeIndex().toUInt()) {
            break
        }

        if (insn.index >= nextLine.index) {
            currentLineNumber = nextLine.lineNumber
            if (lineTableIterator.hasNext()) {
                nextLine = lineTableIterator.next()
            }
        }

        if (currentLineNumber == location.lineNumber()) {
            lineEverMatched = true
            if (insn.opcode.isInvoke()) {
                val methodInfo = dex.allMethods[insn.methodIndex()]
                if (methodInfo != null) {
                    visitOrdinaryFunction(
                        methodInfo.owner,
                        methodInfo.name,
                        methodInfo.signature,
                        insn.opcode.isInvokeStatic()
                    )
                }
            }
        }

        if (inlineCalls.isNotEmpty() && lineEverMatched) {
            while (inlineCalls.first.bciRange.last.toUInt() < insn.index) {
                inlineCalls.pop()
            }
            val inlineCall = inlineCalls.firstOrNull { insn.index.toLong() in it.bciRange }
            if (inlineCall == null) {
                inInline = false
                continue
            } else if (inInline) {
                continue
            }
            inInline = true
            if (inlineCall.isInlineFun) {
                val call = getCalledInlineFunction(debugProcess.positionManager, inlineCall.startLocation) ?: continue
                visitInlineFunction(call)
            } else {
                visitInlineInvokeCall()
            }
        }
    }
}

private val DexMethod.owner: String
    // Drop first 'L' and last ';'
    get() = type.drop(1).dropLast(1)

private val DexMethod.signature: String
    get() = params.joinToString(separator = "", prefix = "(", postfix = ")") + returnType

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

private fun Opcode.isInvoke(): Boolean {
    return this.name.startsWith("INVOKE")
}

private fun Instruction.methodIndex(): UInt {
    return ((payload[2].toUByte().toUInt() shl 8) + payload[1].toUByte().toUInt())
}

private fun Method.getDebugInfo(): DexMethodDebugInfo {
    val lineTable = allLineLocations().map {
        LineTableEntry(it.codeIndex().toUInt(), it.lineNumber())
    }
    return DexMethodDebugInfo(lineTable)
}

// Copied from src/org/jetbrains/kotlin/idea/debugger/stepping/smartStepInto/KotlinSmartStepTargetFiltererAdapter.kt
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
internal data class InlineCallInfo(val isInlineFun: Boolean, val bciRange: LongRange, val startLocation: Location)

private fun extractInlineCalls(location: Location): List<InlineCallInfo> = location.safeMethod()
    ?.getInlineFunctionAndArgumentVariablesToBordersMap()
    ?.toList()
    .orEmpty()
    .map { (variable, locationRange) ->
        InlineCallInfo(
            isInlineFun = variable.name().isInlineFunctionMarkerVariableName,
            bciRange = locationRange.start.codeIndex()..locationRange.endInclusive.codeIndex(),
            startLocation = locationRange.start
        )
    }
    // Filter already visible variable to support smart-step-into while inside an inline function
    .filterNot { location.codeIndex() in it.bciRange }


private suspend fun getCalledInlineFunction(positionManager: PositionManagerAsync, location: Location): KtNamedFunction? {
    val sourcePosition = positionManager.safeGetSourcePositionAsync(location) ?: return null
    return readAction { sourcePosition.elementAt?.parentOfType<KtNamedFunction>() }
}
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
