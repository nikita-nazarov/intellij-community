// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.core

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.impl.DebuggerUtilsImpl
import com.intellij.lang.jvm.actions.AnnotationAttributeValueRequest
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.rt.debugger.MetadataDebugHelper
import com.intellij.rt.debugger.MetadataDebugHelperKotlin
import com.sun.jdi.*
import kotlinx.metadata.jvm.KotlinClassMetadata
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.idea.debugger.base.util.wrapEvaluateException
import org.jetbrains.kotlin.idea.debugger.base.util.wrapIllegalArgumentException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@ApiStatus.Internal
@Service(Service.Level.PROJECT)
class KotlinMetadataDebuggerCacheService private constructor(project: Project) {
    companion object {
        @JvmStatic
        fun getInstance(project: Project): KotlinMetadataDebuggerCacheService = project.service()
    }

    private class KotlinMetadataCacheListener(private val project: Project) : DebuggerManagerListener {
        override fun sessionCreated(session: DebuggerSession) {
            getInstance(project).createCache(session.process)
        }

        override fun sessionRemoved(session: DebuggerSession) {
            getInstance(project).removeCache(session.process)
        }
    }

    // There is one cache per debug process. The size of the list will almost always be 1 when debugging.
    private val caches = mutableListOf<KotlinMetadataCache>()

    fun getKotlinMetadata(refType: ReferenceType, context: EvaluationContext): KotlinClassMetadata? {
        for (cache in caches) {
            if (context.debugProcess === cache.debugProcess) {
                return cache.fetchKotlinMetadata(refType, context)
            }
        }
        return null
    }

    private fun createCache(debugProcess: DebugProcess) {
        caches.add(KotlinMetadataCache(debugProcess))
    }

    private fun removeCache(debugProcess: DebugProcess) {
        caches.removeIf { it.debugProcess === debugProcess }
    }
}

private class KotlinMetadataCache(val debugProcess: DebugProcess)  {
    private class MetadataAdapter(
        val kind: Int,
        val metadataVersion: Array<Int>,
        val data1: Array<String>,
        val data2: Array<String>,
        val extraString: String,
        val packageName: String,
        val extraInt: Int,
    ) {
        @OptIn(ExperimentalEncodingApi::class)
        fun toMetadata(): Metadata {
            return Metadata(
                kind = kind,
                metadataVersion = metadataVersion.toIntArray(),
                data1 = data1.map { String(Base64.Default.decode(it)) }.toTypedArray(),
                data2 = data2,
                extraString = extraString,
                packageName = packageName,
                extraInt = extraInt
            )
        }
    }

    private val cache = mutableMapOf<ReferenceType, KotlinClassMetadata>()

    fun fetchKotlinMetadata(refType: ReferenceType, context: EvaluationContext): KotlinClassMetadata? {
        cache[refType]?.let { return it }
        val metadataAsJson = wrapEvaluateException {
            val value = DebuggerUtilsImpl.invokeHelperMethod(
                context as EvaluationContextImpl,
                MetadataDebugHelper::class.java,
                "getDebugMetadataAsJson",
                listOf(refType.classObject()),
                true
            )
            (value as? StringReference)?.value()
        } ?: return null
        val metadata = wrapJsonSyntaxException {
            Gson().fromJson(metadataAsJson, MetadataAdapter::class.java).toMetadata()
        } ?: return null

        val parsedMetadata = wrapIllegalArgumentException {
            KotlinClassMetadata.readStrict(metadata)
        } ?: return null

        cache[refType] = parsedMetadata
        return parsedMetadata
    }
}

private fun <T> wrapJsonSyntaxException(block: () -> T): T? {
    return try {
        block()
    } catch (e: JsonSyntaxException) {
        null
    }
}
