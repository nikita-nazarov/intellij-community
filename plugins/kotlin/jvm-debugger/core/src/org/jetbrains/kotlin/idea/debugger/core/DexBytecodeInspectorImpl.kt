// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.debugger.core

import com.android.ddmlib.IDevice
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkFileUnit
import com.android.tools.idea.run.ApkProvider
import com.android.zipflinger.ZipRepo
import com.intellij.debugger.engine.PositionManagerAsync
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.util.parentOfType
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.sun.jdi.Location
import com.sun.jdi.Method
import kexter.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.idea.debugger.base.util.safeGetSourcePositionAsync
import org.jetbrains.kotlin.idea.debugger.base.util.safeLocation
import org.jetbrains.kotlin.idea.debugger.base.util.safeMethod
import org.jetbrains.kotlin.idea.debugger.core.InlineCallInfo
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.*
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.utils.addToStdlib.popLast
import java.util.*

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
        val (expression, debugProcess, _, _) = context
        val location = debugProcess.suspendManager.pausedContext?.frameProxy?.safeLocation()
            ?: return targets
        val method = location.safeMethod() ?: return targets
        val allLocations = method.allLineLocations()

        val project = debugProcess.project
        val module = runBlocking { readAction {
                val file = expression.containingFile.virtualFile
                ProjectFileIndex.getInstance(project).getModuleForFile(file)
        } } ?: return targets
        //val paths = com.intellij.openapi.compiler.CompilerPaths.getOutputPaths(arrayOf(module)).toList()
        val environment = (debugProcess.session.xDebugSession as? XDebugSessionImpl)?.executionEnvironment ?: return targets
        val configuration = environment.runProfile as? AndroidRunConfiguration ?: return targets
        val apkProvider = configuration.apkProvider ?: return targets
        val devicesFutures = configuration.deployTargetContext.currentDeployTargetProvider.getDeployTarget(project).getDevices(project)
        // Waiting for devices to become online can hang the debugger for a while, so fetch only the ones that are running
        val devices = devicesFutures.get().mapNotNull {
            if (it.isDone) {
                it.get()
            } else {
                null
            }
        }
        val apkFiles = findApkFilesOfModule(devices, apkProvider, module)
        val dex = findDexWithLocation(apkFiles, location) ?: return targets
        val methodIndexToMethod = buildMap {
            for (dexClass in dex.classes.values) {
                for (dexMethod in dexClass.methods.values) {
                    put(dexMethod.index, dexMethod)
                }
            }
        }
        val debugInfo = DexMethodDebugInfo(allLocations.map { LineTableEntry(it.codeIndex().toUInt(), it.lineNumber()) })
        val methodBytecode = DexBytecode.fromBytes(method.bytecodes(), debugInfo)
        val lineTableEntries = LinkedList(debugInfo.lineTable)
        var currentLineNumber: Int? = null
        val invokesOnSameLineBeforeCurrentLocation = mutableListOf<Instruction>()
        var lineEverMatched = false
        var inInline = false
        val locationIndex = location.codeIndex()
        val inlineCalls= extractInlineCalls(location)
        val visitedInlineCalls = mutableListOf<InlineCallInfo>()
        val visitedInlineInvokeCalls = mutableListOf<InlineCallInfo>()
        for (insn in methodBytecode.instructions) {
            if (insn.index >= locationIndex.toUInt()) {
                break
            }

            val first = lineTableEntries.peekFirst()
            if (insn.index >= first.index) {
                currentLineNumber = first.lineNumber
                lineTableEntries.pop()
            }

            if (currentLineNumber == location.lineNumber()) {
                lineEverMatched = true
                if (insn.opcode.isInvoke()) {
                    invokesOnSameLineBeforeCurrentLocation.add(insn)
                }
            }

            if (lineEverMatched) {
                val inlineCall = inlineCalls.firstOrNull { insn.index.toLong() in it.bciRange }
                if (inlineCall == null) {
                    inInline = false
                    continue
                }
                if (inInline) continue
                inInline = true
                if (inlineCall.isInlineFun) {
                    visitedInlineCalls.add(inlineCall)
                } else {
                    visitedInlineInvokeCalls.add(inlineCall)
                }
            }
        }

        val signaturesOfMethodsToFilter = invokesOnSameLineBeforeCurrentLocation
            .mapNotNull { methodIndexToMethod[it.methodIndex()]?.getBytecodeSignature() }
        val filterer = KotlinSmartStepTargetFilterer(targets, debugProcess)
        for (bytecodeSignature in signaturesOfMethodsToFilter) {
            with(bytecodeSignature) {
                runBlocking {
                    filterer.visitOrdinaryFunction(owner, name, signature)
                }
            }
        }

        for (inlineCall in visitedInlineCalls) {
            runBlocking {
                val call = getCalledInlineFunction(debugProcess.positionManager, inlineCall.startLocation) ?: return@runBlocking
                filterer.visitInlineFunction(call)
            }
        }
        return filterer.getUnvisitedTargets()
    }

    private fun findDexWithLocation(apkFiles: List<ApkFileUnit>, location: Location): Dex? {
        for (apk in apkFiles) {
            val zipRepo = ZipRepo(apk.apkPath)
            val dexEntries = zipRepo.entries.filter { (name, _) -> name.endsWith(".dex") }
            for (entry in dexEntries) {
                val content = zipRepo.getContent(entry.key).array()
                val dex = Dex.fromBytes(content)
                val signature = location.declaringType().signature()
                val containsLocation = dex.classes.any { (name, _) ->
                    name == signature
                }

                if (containsLocation) {
                    return dex
                }
            }
        }
        return null
    }

    private fun findApkFilesOfModule(devices: List<IDevice>, apkProvider: ApkProvider, module: Module): List<ApkFileUnit> {
        val result = mutableListOf<ApkFileUnit>()
        for (device in devices) {
            for (apk in apkProvider.getApks(device)) {
                for (file in apk.files) {
                    // Intellij modules have either same or larger name than apk modules
                    if (module.name.startsWith(file.moduleName)) {
                        result.add(file)
                    }
                }
            }
        }
        return result
    }
}

private fun DexMethod.getBytecodeSignature(): BytecodeSignature {
    // Drop first 'L' and last ';'
    val owner = type.drop(1).dropLast(1)
    val signature = params.joinToString(separator = "", prefix = "(", postfix = ")") + returnType
    return BytecodeSignature(owner, name, signature)
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

private fun Opcode.isInvoke(): Boolean {
    return this.name.startsWith("INVOKE")
}

private fun Instruction.methodIndex(): UInt {
    return ((payload[2].toUByte().toUInt() shl 8) + payload[1].toUByte().toUInt())
}

// TODO move these declarations to utils of some sort
// Copied from src/org/jetbrains/kotlin/idea/debugger/stepping/smartStepInto/KotlinSmartStepTargetFiltererAdapter.kt
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
