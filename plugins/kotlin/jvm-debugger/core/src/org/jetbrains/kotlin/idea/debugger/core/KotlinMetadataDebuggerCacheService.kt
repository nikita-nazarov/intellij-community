// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.debugger.core

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.debugger.engine.DebugProcess
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.impl.DebuggerManagerListener
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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

    fun getKotlinMetadata2(refType: ReferenceType, context: EvaluationContext): KotlinClassMetadata? {
        for (cache in caches) {
            if (context.debugProcess === cache.debugProcess) {
                return cache.fetchKotlinMetadata2(refType, context)
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
    // The purpose of this class is to prevent searching for
    // the MetadataUtilKt class and `getDebugMetadataAsJson` method
    // multiple times.
    private sealed class MetadataJdiFetcher {
        companion object {
            private const val METADATA_UTILS_CLASS_NAME = "kotlin.jvm.internal.MetadataDebugUtilKt"
            private const val GET_DEBUG_METADATA_AS_JSON = "getDebugMetadataAsJson"

            fun getInstance(context: EvaluationContext): MetadataJdiFetcher {
                val metadataUtilClass = wrapEvaluateException {
                    context.debugProcess.findClass(context, METADATA_UTILS_CLASS_NAME, null)
                } as? ClassType ?: return FailedToInitialize
                val getDebugMetadataAsJsonMethod = metadataUtilClass.methodsByName(GET_DEBUG_METADATA_AS_JSON).singleOrNull()
                    ?: return FailedToInitialize
                return Initialized(metadataUtilClass, getDebugMetadataAsJsonMethod)
            }
        }

        data object FailedToInitialize : MetadataJdiFetcher()
        class Initialized(
            private val metadataUtilClass: ClassType,
            private val getDebugMetadataAsJsonMethod: Method
        ) : MetadataJdiFetcher() {
            fun fetchMetadataAsJson(refType: ReferenceType, context: EvaluationContext): String? {
                val classObject = refType.classObject() ?: return null
                val stringRef = wrapEvaluateException {
                    context.debugProcess.invokeMethod(
                        context, metadataUtilClass, getDebugMetadataAsJsonMethod, listOf(classObject)
                    )
                } as? StringReference
                return stringRef?.value()
            }
        }
    }

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

    private class MetadataAdapter2 {
        var kind: Int = 0
        var metadataVersion: Array<Int> = emptyArray()
        var data1: Array<String> = emptyArray()
        var data2: Array<String> = emptyArray()
        var extraString: String = ""
        var packageName: String = ""
        var extraInt: Int = 0

        fun toMetadata(): Metadata {
            return Metadata(
                kind = kind,
                metadataVersion = metadataVersion.toIntArray(),
                data1 = data1,
                data2 = data2,
                extraString = extraString,
                packageName = packageName,
                extraInt = extraInt
            )
        }
    }

    private val cache = mutableMapOf<ReferenceType, KotlinClassMetadata>()
    private lateinit var metadataJdiFetcher: MetadataJdiFetcher

    fun fetchKotlinMetadata2(refType: ReferenceType, context: EvaluationContext): KotlinClassMetadata? {
        val classObject = refType.classObject()
        val metadataClass = wrapEvaluateException {
            debugProcess.findClass(context, "kotlin.Metadata", null)
        } ?: return null
        val getAnnotation = DebuggerUtils.findMethod(classObject.referenceType(), "getAnnotation", null)
            ?: return null
        val metadataRef = debugProcess.invokeMethod(
            context, classObject, getAnnotation, listOf(metadataClass.classObject())
        ) as? ObjectReference ?: return null
        val metadataAdapter = MetadataAdapter2()
        val methods = metadataRef.referenceType().methods()
        for (method in methods) {
            fun invoke(): Value? {
                return debugProcess.invokeMethod(context, metadataRef, method, emptyList())
            }

            when (method.name()) {
                "d1" -> {
                    val array = (invoke() as? ArrayReference)?.toStringArray() ?: return null
                    metadataAdapter.data1 = array
                }
                "d2" -> {
                    val array = (invoke() as? ArrayReference)?.toStringArray() ?: return null
                    metadataAdapter.data2 = array
                }
                "k" -> {
                    val value = (invoke() as? IntegerValue)?.value() ?: return null
                    metadataAdapter.kind = value
                }
                "mv" -> {
                    val array = (invoke() as? ArrayReference)?.toIntArray() ?: return null
                    metadataAdapter.metadataVersion = array
                }
                "xs" -> {
                    val value = (invoke() as? StringReference)?.value() ?: return null
                    metadataAdapter.extraString = value
                }
                "pn" -> {
                    val value = (invoke() as? StringReference)?.value() ?: return null
                    metadataAdapter.packageName = value
                }
                "xi" -> {
                    val value = (invoke() as? IntegerValue)?.value() ?: return null
                    metadataAdapter.extraInt = value
                }
            }
        }
        return wrapIllegalArgumentException {
            KotlinClassMetadata.readStrict(metadataAdapter.toMetadata())
        }
    }

    fun fetchKotlinMetadata(refType: ReferenceType, context: EvaluationContext): KotlinClassMetadata? {
        if (context.debugProcess !== debugProcess) {
            return null
        }

        if (!::metadataJdiFetcher.isInitialized) {
            metadataJdiFetcher = MetadataJdiFetcher.getInstance(context)
        }

        when (val fetcher = metadataJdiFetcher) {
            is MetadataJdiFetcher.FailedToInitialize -> return null
            is MetadataJdiFetcher.Initialized -> {
                //cache[refType]?.let { return it }

                val metadataAsJson = fetcher.fetchMetadataAsJson(refType, context) ?: return null
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
    }
}

private fun ArrayReference.toStringArray(): Array<String>? {
    val result = Array(length()) { "" }
    for (i in 0..<length()) {
        val value = (getValue(i) as? StringReference)?.value() ?: return null
        result[i] = value
    }
    return result
}

private fun ArrayReference.toIntArray(): Array<Int>? {
    val result = Array(length()) { 0 }
    for (i in 0..<length()) {
        val value = (getValue(i) as? IntegerValue)?.value() ?: return null
        result[i] = value
    }
    return result
}

private fun <T> wrapJsonSyntaxException(block: () -> T): T? {
    return try {
        block()
    } catch (e: JsonSyntaxException) {
        null
    }
}
