// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.debugger.stepping.smartStepInto

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.openapi.application.readAction
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtNamedFunction

class KotlinSmartStepTargetFilterer(
    private val targets: List<KotlinMethodSmartStepTarget>,
    private val debugProcess: DebugProcessImpl
) {
    private val functionCounter = mutableMapOf<String, Int>()
    private val targetWasVisited = BooleanArray(targets.size) { false }

    suspend fun visitInlineFunction(function: KtNamedFunction) {
        val label = readAction {
            analyze(function) {
                val symbol = function.symbol
                KotlinMethodSmartStepTarget.calcLabel(symbol)
            }
        }
        val currentCount = functionCounter.increment(label) - 1
        val matchedSteppingTargetIndex = targets.indexOfFirst {
            it.getDeclaration() === function && it.ordinal == currentCount
        }
        if (matchedSteppingTargetIndex < 0) return
        targetWasVisited[matchedSteppingTargetIndex] = true
    }

    fun visitInlineInvokeCall() {
        val matchedSteppingTargetIndex = targets.indexOfFirst {
            it.methodInfo.isInvoke && it.ordinal == functionCounter.getOrDefault(it.label, 0)
        }
        if (matchedSteppingTargetIndex < 0) return
        val labelFound = targets[matchedSteppingTargetIndex].label
        if (labelFound != null) functionCounter.increment(labelFound)

        targetWasVisited[matchedSteppingTargetIndex] = true
    }

    suspend fun visitOrdinaryFunction(owner: String, name: String, signature: String) {
        val currentCount = functionCounter.increment("$owner.$name$signature") - 1
        for ((i, target) in targets.withIndex()) {
            if (targetWasVisited[i]) continue
            val bytecodeSignature = BytecodeSignature(owner, name, signature)
            if (KotlinSmartSteppingUtils.shouldBeFilteredOut(target, debugProcess, bytecodeSignature, currentCount)) {
                targetWasVisited[i] = true
                break
            }
        }
    }

    fun getUnvisitedTargets(): List<KotlinMethodSmartStepTarget> =
        targets.filterIndexed { i, _ ->
            !targetWasVisited[i]
        }
}

private fun MutableMap<String, Int>.increment(key: String): Int {
    val newValue = (get(key) ?: 0) + 1
    put(key, newValue)
    return newValue
}
