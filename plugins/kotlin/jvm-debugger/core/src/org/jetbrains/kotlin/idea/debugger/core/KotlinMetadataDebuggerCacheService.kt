// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.core

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.impl.DebuggerUtilsImpl
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.rt.debugger.MetadataDebugHelper
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.Value
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.kotlin.idea.debugger.base.util.wrapEvaluateException
import org.jetbrains.kotlin.idea.debugger.base.util.wrapIllegalArgumentException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.metadata.jvm.KotlinClassMetadata

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
        return getCache(context)?.fetchKotlinMetadata(refType, context)
    }

    fun getKotlinMetadataList(refTypes: Collection<ReferenceType>, context: EvaluationContext): List<KotlinClassMetadata>? {
	    return getCache(context)?.fetchKotlinMetadataList(refTypes, context)
    }

    private fun getCache(context: EvaluationContext): KotlinMetadataCache? {
	return caches.firstOrNull { it.debugProcess == context.debugProcess }
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
        val metadataAsJson = callMethodFromHelper(
            context, "getDebugMetadataAsJson", listOf(refType.classObject())
        ) ?: return null
        val metadata = parseMetadataFromJson(metadataAsJson) ?: return null
        cache[refType] = metadata
        return metadata
    }

    fun fetchKotlinMetadataList(refTypes: Collection<ReferenceType>, context: EvaluationContext): List<KotlinClassMetadata>? {
        val result = mutableListOf<KotlinClassMetadata>()
        val toFetch = mutableListOf<ReferenceType>()
        for (refType in refTypes) {
            val metadata = cache[refType]
            if (metadata != null) {
                result.add(metadata)
            } else {
                toFetch.add(refType)
            }
        }

        if (toFetch.isEmpty()) {
            return result
        }

        val classObjects = toFetch.map { it.classObject() }
        val concatenatedJsonMetadatas = callMethodFromHelper(
            context, "getDebugMetadataListAsJson", classObjects
        ) ?: return null
        val splitJsonMetadatas = concatenatedJsonMetadatas.split(MetadataDebugHelper.METADATA_SEPARATOR)
        if (splitJsonMetadatas.size != toFetch.size) {
            return null
        }

        for ((metadataAsJson, refType) in splitJsonMetadatas.zip(toFetch)) {
            val metadata = parseMetadataFromJson(metadataAsJson) ?: return null
            cache[refType] = metadata
            result.add(metadata)
        }
        return result
    }

    private fun parseMetadataFromJson(metadataAsJson: String): KotlinClassMetadata? {
        val metadata = wrapJsonSyntaxException {
            Gson().fromJson(metadataAsJson, MetadataAdapter::class.java).toMetadata()
        } ?: return null
        val parsedMetadata = wrapIllegalArgumentException {
            KotlinClassMetadata.readStrict(metadata)
        } ?: return null
        return parsedMetadata
    }

    private fun callMethodFromHelper(context: EvaluationContext, methodName: String, args: List<Value>): String? {
        return wrapEvaluateException {
            val value = DebuggerUtilsImpl.invokeHelperMethod(
                context as EvaluationContextImpl,
                MetadataDebugHelper::class.java,
                methodName,
                args,
                true
            )
            (value as? StringReference)?.value()
        }
    }
}

private fun <T> wrapJsonSyntaxException(block: () -> T): T? {
    return try {
        block()
    } catch (_: JsonSyntaxException) {
        null
    }
}
