// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

// The package directive doesn't match the file location to prevent API breakage
package org.jetbrains.kotlin.idea.debugger

import com.intellij.debugger.JavaDebuggerBundle
import com.intellij.debugger.engine.DebuggerManagerThreadImpl
import com.intellij.debugger.engine.DebuggerUtils
import com.intellij.debugger.engine.JVMNameUtil
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.impl.DebuggerUtilsAsync
import com.intellij.debugger.settings.NodeRendererSettings
import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl
import com.intellij.debugger.ui.tree.DebuggerTreeNode
import com.intellij.debugger.ui.tree.NodeManager
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.ui.tree.render.ChildrenBuilder
import com.intellij.debugger.ui.tree.render.ClassRenderer
import com.intellij.debugger.ui.tree.render.DescriptorLabelListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.sun.jdi.*
import org.jetbrains.kotlin.idea.debugger.base.util.safeFields
import org.jetbrains.kotlin.idea.debugger.base.util.safeType
import org.jetbrains.kotlin.idea.debugger.base.util.isLateinitVariableGetter
import org.jetbrains.kotlin.idea.debugger.base.util.isSimpleGetter
import org.jetbrains.kotlin.idea.debugger.core.render.GetterDescriptor
import org.jetbrains.kotlin.idea.debugger.core.KotlinDebuggerCoreBundle
import org.jetbrains.kotlin.idea.debugger.core.isInKotlinSources
import org.jetbrains.kotlin.idea.debugger.core.isInKotlinSourcesAsync
import kotlinx.metadata.isNotDefault
import kotlinx.metadata.jvm.KotlinClassMetadata
import org.jetbrains.kotlin.idea.debugger.core.*
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import kotlin.system.measureTimeMillis

class KotlinClassRenderer : ClassRenderer() {
    init {
        setIsApplicableChecker(Function { type: Type? ->
            if (type is ReferenceType && type !is ArrayType && !type.canBeRenderedBetterByPlatformRenderers()) {
                return@Function type.isInKotlinSourcesAsync()
            }
            CompletableFuture.completedFuture(false)
        })
    }

    override fun buildChildren(value: Value?, builder: ChildrenBuilder, evaluationContext: EvaluationContext) {
        DebuggerManagerThreadImpl.assertIsManagerThread()
        if (value !is ObjectReference) {
            builder.setChildren(emptyList())
            return
        }

        runBenchmark(evaluationContext)

        val parentDescriptor = builder.parentDescriptor as ValueDescriptorImpl
        val nodeManager = builder.nodeManager
        val nodeDescriptorFactory = builder.descriptorManager
        val refType = value.referenceType()
        val gettersFuture = DebuggerUtilsAsync.allMethods(refType)
            .thenApply { methods ->
                val getters = fetchGettersUsingMetadata(evaluationContext, methods) ?: methods.getters()
                getters.createNodes(value, parentDescriptor.project, evaluationContext, nodeManager)
            }
        DebuggerUtilsAsync.allFields(refType).thenCombine(gettersFuture) { fields, getterNodes ->
            if (fields.isEmpty() && getterNodes.isEmpty()) {
                builder.setChildren(listOf(nodeManager.createMessageNode(KotlinDebuggerCoreBundle.message("message.class.has.no.properties"))))
                return@thenCombine
            }

            createNodesToShow(fields, evaluationContext, parentDescriptor, nodeManager, nodeDescriptorFactory, value)
                .thenAccept { nodesToShow ->
                    if (nodesToShow.isEmpty()) {
                        val classHasNoFieldsToDisplayMessage =
                            nodeManager.createMessageNode(
                                JavaDebuggerBundle.message("message.node.class.no.fields.to.display")
                            )
                        builder.setChildren(
                            listOf(classHasNoFieldsToDisplayMessage) +
                            getterNodes
                        )
                        return@thenAccept
                    }
                    builder.setChildren(mergeNodesLists(nodesToShow, getterNodes))
                }
        }
    }

    private fun fetchGettersUsingMetadata(
        context: EvaluationContext,
        methods: List<Method>
    ): List<Method>? {
        val gettersToShow = methods
            .map { it.declaringType() }
            .toSet()
            .calculateGettersToShow(context)
            ?: return null
        return methods
            .filter { it.name() in gettersToShow }
            .distinctBy { it.name() }
    }

    private fun runBenchmark(context: EvaluationContext) {
        val fileName = Registry.get("debugger.metadata.benchmark.file.name").asString()
        val metadataCache = KotlinMetadataDebuggerCacheService.getInstance(context.project)
        val metadataFetcher: (ReferenceType) -> KotlinClassMetadata? =
            if (Registry.get("debugger.metadata.benchmark.method").asInteger() == 1) {
                { metadataCache.getKotlinMetadata(it, context) }
            } else {
                { metadataCache.getKotlinMetadata2(it, context) }
            }

        fun runBenchmark(classPrefix: String, classNum: Int) {
            val classes = buildList {
                for (i in 0..<classNum) {
                    val className = "com.example.myapplication.${classPrefix}_$i"
                    val clazz =
                        context.debugProcess.findClass(context, className, null) ?: throw IllegalStateException("$className not found")
                    add(clazz)
                }
            }

            val times = mutableListOf<Long>()
            repeat(10) {
                val time = measureTimeMillis {
                    for (i in 0..<classNum) {
                        val clazz = classes[i]
                        metadataFetcher(clazz) ?: throw IllegalStateException("Couldn't fetch metadata for ${clazz.name()}")
                    }
                }
                times.add(time)
            }

            File(fileName).appendText("${classNum}, ${times.average()}\n")
        }

        File(fileName).appendText("Number of classes, Average time\n")
        try {
            runBenchmark("A1", 10)
            runBenchmark("A2", 50)
            runBenchmark("A3", 100)
            runBenchmark("A4", 250)
            runBenchmark("A5", 500)
            runBenchmark("A6", 1000)
        } catch (ex: IllegalStateException) {
            File("/tmp/errors.txt").appendText("${ex.message}\n")
        }
    }

    private fun Collection<ReferenceType>.calculateGettersToShow(context: EvaluationContext): Set<String>? {
        val metadataCache = KotlinMetadataDebuggerCacheService.getInstance(context.project)
        val gettersToShow = mutableSetOf<String>()
        for (type in this) {
            if (!type.isInKotlinSources()) {
                continue
            }
            val metadata = metadataCache.getKotlinMetadata(type, context)
            if (metadata !is KotlinClassMetadata.Class) {
                return null
            }
            for (property in metadata.kmClass.properties) {
                if (property.getter.isNotDefault) {
                    gettersToShow.add("get${property.name.capitalizeAsciiOnly()}")
                }
            }
        }
        return gettersToShow
    }

    override fun calcLabel(
        descriptor: ValueDescriptor,
        evaluationContext: EvaluationContext?,
        labelListener: DescriptorLabelListener
    ): String {
        val renderer = NodeRendererSettings.getInstance().toStringRenderer
        return renderer.calcLabel(descriptor, evaluationContext, labelListener)
    }

    override fun isApplicable(type: Type?) = throw IllegalStateException("Should not be called")

    override fun shouldDisplay(context: EvaluationContext?, objInstance: ObjectReference, field: Field): Boolean {
        val referenceType = objInstance.referenceType()
        if (field.isStatic && referenceType.isKotlinObjectType()) {
            return !field.isInstanceFieldOfType(referenceType)
        }
        return super.shouldDisplay(context, objInstance, field)
    }

    private fun mergeNodesLists(fieldNodes: List<DebuggerTreeNode>, getterNodes: List<DebuggerTreeNode>): List<DebuggerTreeNode> {
        val namesToIndex = getterNodes.withIndex().associate { it.value.descriptor.name to it.index }

        val result = ArrayList<DebuggerTreeNode>(fieldNodes.size + getterNodes.size)
        val added = BooleanArray(getterNodes.size)
        for (fieldNode in fieldNodes) {
            result.add(fieldNode)
            val name = fieldNode.descriptor.name.removeSuffix("\$delegate")
            val index = namesToIndex[name] ?: continue
            if (!added[index]) {
                result.add(getterNodes[index])
                added[index] = true
            }
        }

        result.addAll(getterNodes.filterIndexed { i, _ -> !added[i] })
        return result
    }

    private fun List<Method>.getters() =
        filter { method ->
            !method.isAbstract &&
            GetterDescriptor.GETTER_PREFIXES.any { method.name().startsWith(it) } &&
            method.argumentTypeNames().isEmpty() &&
            method.name() != "getClass" &&
            !method.name().endsWith("\$annotations") &&
            method.declaringType().isInKotlinSources() &&
            !method.isSimpleGetter() &&
            !method.isLateinitVariableGetter()
        }
        .distinctBy { it.name() }
        .toList()

    private fun List<Method>.createNodes(
        parentObject: ObjectReference,
        project: Project,
        evaluationContext: EvaluationContext,
        nodeManager: NodeManager
    ) = map { nodeManager.createNode(GetterDescriptor(parentObject, it, project), evaluationContext) }

    private fun ReferenceType.isKotlinObjectType() =
        containsInstanceField() && hasPrivateConstructor()

    private fun ReferenceType.containsInstanceField() =
        safeFields().any { it.isInstanceFieldOfType(this) }

    private fun ReferenceType.hasPrivateConstructor(): Boolean {
        val constructor = methodsByName(JVMNameUtil.CONSTRUCTOR_NAME).singleOrNull() ?: return false
        return constructor.isPrivate && constructor.argumentTypeNames().isEmpty()
    }

    /**
     * IntelliJ Platform has good collections' debugger renderers.
     *
     * We want to use them even when the collection is implemented completely in Kotlin
     * (e.g. lists, sets and maps empty singletons; subclasses of `Abstract(List|Set|Map)`;
     * collections, built by `build(List|Set|Map) { ... }` methods).
     *
     * Also we want to use platform renderer for Map entries.
     */
    private fun ReferenceType.canBeRenderedBetterByPlatformRenderers(): Boolean {
        val typesWithGoodDefaultRenderers = listOf(
            "java.util.Collection",
            "java.util.Map",
            "java.util.Map.Entry",
        )

        return typesWithGoodDefaultRenderers.any { superType -> DebuggerUtils.instanceOf(this, superType) }
    }

    private fun Field.isInstanceFieldOfType(type: Type) =
        isStatic && isFinal && name() == "INSTANCE" && safeType() == type
}
