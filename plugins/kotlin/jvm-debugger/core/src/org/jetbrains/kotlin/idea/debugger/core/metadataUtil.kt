// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.debugger.core

import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.sun.jdi.ArrayReference
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import kotlinx.metadata.jvm.KotlinClassMetadata
import org.jetbrains.kotlin.idea.debugger.KotlinPositionManager
import java.io.File

fun fetchKotlinMetadata(refType: ReferenceType, evaluationContext: EvaluationContext): KotlinClassMetadata? {
    val metadataStr = fetchMetadataAnnotationString(refType, evaluationContext) ?: return null
    val metadata = parseMetadata(metadataStr) ?: return null
    //val m1 = KotlinPositionManager::class.java.getAnnotation(Metadata::class.java)
    //val m2 = parseMetadata(m1.toString())
    return KotlinClassMetadata.readStrict(metadata)
}

private const val KOTLIN_METADATA = "@kotlin.Metadata"

private fun parseMetadata(metadataString: String): Metadata? {
    // Drop '@kotlin.Metadata(' and ')'
    val data = metadataString.substring(KOTLIN_METADATA.length + 1, metadataString.length - 1)
    return MetadataParser(data.toCharArray()).parseMetadata()
}

class MetadataBuilder(
    var kind: Int? = null,
    var metadataVersion: IntArray? = null,
    var bytecodeVersion: IntArray? = null,
    var data1: Array<String>? = null,
    var data2: Array<String>? = null,
    var extraString: String? = null,
    var packageName: String? = null,
    var extraInt: Int? = null,
) {
    fun toMetadata(): Metadata? {
        return Metadata(
            kind ?: return null,
            metadataVersion ?: return null,
            bytecodeVersion ?: return null,
            data1 ?: return null,
            data2 ?: return null,
            extraString ?: return null,
            packageName ?: return null,
            extraInt ?: return null
        )
    }
}

/*
    val metadataClass = debugProcess.findClass(evaluationContext, "kotlin.Metadata", null) ?: return null
    for (method in metadataClass.methods()) {
        val returnValue = debugProcess.invoke(evaluationContext, annotation, method, null)
        when (method.name()) {
            "k" -> ...
             ...
        }
    }
 */
private fun fetchMetadataAnnotationString(refType: ReferenceType, evaluationContext: EvaluationContext): String? {
    val toString = refType.methodsByName("toString").singleOrNull() ?: return null
    val annotations = fetchAnnotations(refType, evaluationContext) ?: return null
    for (i in 0 until annotations.length()) {
        val annotation = annotations.getValue(i) as? ObjectReference ?: return null
        val strRef = evaluationContext.debugProcess.invokeMethod(
            evaluationContext, annotation, toString, emptyList()
        ) as? StringReference ?: return null
        val str = strRef.value()
        if (str.startsWith(KOTLIN_METADATA)) {
            return str
        }
    }
    return null
}

private fun fetchAnnotations(refType: ReferenceType, evaluationContext: EvaluationContext): ArrayReference? {
    val classObject = refType.classObject()
    val classType = classObject.referenceType()
    val getAnnotations = classType.methodsByName("getAnnotations").singleOrNull() ?: return null
    return evaluationContext.debugProcess.invokeMethod(
        evaluationContext, classObject, getAnnotations, emptyList()
    ) as? ArrayReference
}
