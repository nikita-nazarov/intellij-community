// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.debugger.core

import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.sun.jdi.Method
import kexter.*
import com.intellij.openapi.module.Module
import org.jetbrains.kotlin.idea.debugger.base.util.safeLocation
import org.jetbrains.kotlin.idea.debugger.base.util.safeMethod
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.KotlinMethodSmartStepTarget
import org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto.SmartStepIntoContext
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkProvider
import com.android.ddmlib.IDevice
import com.android.tools.idea.run.ApkFileUnit
import com.android.tools.layoutinspector.toInt
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.zipflinger.ZipRepo
import com.intellij.openapi.progress.runBlockingCancellable
import com.google.common.util.concurrent.ListenableFuture
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.util.io.toByteArray
import com.sun.jdi.Location
import kexter.core.DexReader
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import java.util.LinkedList
import java.util.zip.ZipFile
import kotlin.math.sign

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

    override suspend fun filterAlreadyExecutedTargets(
        targets: List<KotlinMethodSmartStepTarget>,
        context: SmartStepIntoContext
    ): List<KotlinMethodSmartStepTarget> {
        val (expression, debugProcess, _, _) = context
        val location = debugProcess.suspendManager.pausedContext?.frameProxy?.safeLocation()
            ?: return targets
        val method = location.safeMethod() ?: return targets
        val allLocations = method.allLineLocations()

        val project = debugProcess.project
        val module = readAction {
                val file = expression.containingFile.virtualFile
                ProjectFileIndex.getInstance(project).getModuleForFile(file)
        } ?: return targets
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
        val methodIndexToName = buildMap {
            for (dexClass in dex.classes.values) {
                for (dexMethod in dexClass.methods.values) {
                    put(dexMethod.index, dexMethod.name)
                }
            }
        }
        val debugInfo = DexMethodDebugInfo(allLocations.map { LineTableEntry(it.codeIndex().toUInt(), it.lineNumber()) })
        val methodBytecode = DexBytecode.fromBytes(method.bytecodes(), debugInfo)
        val lineTableEntries = LinkedList(debugInfo.lineTable)
        var currentLineNumber: Int? = null
        val invokesOnSameLineBeforeCurrentLocation = mutableListOf<Instruction>()
        val inlineCalls = extractInlineCalls(location)
        for (insn in methodBytecode.instructions) {
            if (insn.index >= location.codeIndex().toUInt()) {
                break
            }

            val first = lineTableEntries.peekFirst()
            if (insn.index >= first.index) {
                currentLineNumber = first.lineNumber
                lineTableEntries.pop()
            }

            if (currentLineNumber == location.lineNumber() && insn.opcode.isInvoke()) {
                invokesOnSameLineBeforeCurrentLocation.add(insn)
            }
        }
        val namesToFilter = invokesOnSameLineBeforeCurrentLocation
            .mapNotNull { methodIndexToName[it.methodIndex()] }
            .toSet()
        return targets.filter { it.methodInfo.name !in namesToFilter }
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
internal data class InlineCallInfo(val variableName: String, val bciRange: LongRange, val startLocation: Location)

private fun extractInlineCalls(location: Location): List<InlineCallInfo> = location.safeMethod()
    ?.getInlineFunctionAndArgumentVariablesToBordersMap()
    ?.toList()
    .orEmpty()
    .map { (variable, locationRange) ->
        InlineCallInfo(
            variableName = variable.name(),
            bciRange = locationRange.start.codeIndex()..locationRange.endInclusive.codeIndex(),
            startLocation = locationRange.start
        )
    }
    // Filter already visible variable to support smart-step-into while inside an inline function
    .filterNot { location.codeIndex() in it.bciRange }
