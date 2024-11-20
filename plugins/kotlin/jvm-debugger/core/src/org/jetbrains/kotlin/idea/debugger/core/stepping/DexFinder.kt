// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.debugger.core.stepping

import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkFileUnit
import com.android.zipflinger.ZipRepo
import com.intellij.openapi.module.Module
import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.openapi.application.readAction
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.xdebugger.impl.XDebugSessionImpl
import com.sun.jdi.Location
import kexter.Dex
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

object DexFinder {
    fun findDexWithLocation(apkFiles: List<ApkFileUnit>, location: Location): Dex? {
        val signature = location.declaringType().signature()
        for (apk in apkFiles) {
            val zipRepo = ZipRepo(apk.apkPath)
            val dexEntries = zipRepo.entries.filter { (name, _) -> name.endsWith(".dex") }
            for (entry in dexEntries) {
                val content = zipRepo.getContent(entry.key).array()
                val dex = Dex.fromBytes(content)
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

    suspend fun findApksBySourceElement(debugProcess: DebugProcessImpl, element: KtElement): List<ApkFileUnit> {
        val configuration = debugProcess.androidRunConfiguration ?: return emptyList()
        val apkProvider = configuration.apkProvider ?: return emptyList()
        val module = findModule(element) ?: return emptyList()
        val project = debugProcess.project
        val devicesFutures = configuration.deployTargetContext
            .currentDeployTargetProvider
            .getDeployTarget(project)
            .getDevices(project)

        // Waiting for devices to become online can hang the debugger for a while, so fetch only the ones that are running
        val devices = devicesFutures.get().mapNotNull {
            if (it.isDone) {
                it.get()
            } else {
                null
            }
        }

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

private suspend fun findModule(element: KtElement): Module? {
    return readAction {
        val file = element.containingFile.virtualFile
        ProjectFileIndex.getInstance(element.project).getModuleForFile(file)
    }
}

private val DebugProcessImpl.androidRunConfiguration: AndroidRunConfiguration?
    get() {
        return session
            .xDebugSession.safeAs<XDebugSessionImpl>()
            ?.executionEnvironment
            ?.runProfile?.safeAs<AndroidRunConfiguration>()
    }
