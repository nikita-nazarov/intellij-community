/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.project

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.cli.common.arguments.CommonCompilerArguments
import org.jetbrains.kotlin.cli.common.parser.com.sampullara.cli.Argument
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettings
import org.jetbrains.kotlin.idea.facet.getLibraryLanguageLevel
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.resolve.TargetPlatform

val KtElement.platform: TargetPlatform
    get() = TargetPlatformDetector.getPlatform(containingKtFile)

val KtElement.builtIns: KotlinBuiltIns
    get() = getResolutionFacade().moduleDescriptor.builtIns

private val multiPlatformProjectsArg: String by lazy {
    "-" + CommonCompilerArguments::multiPlatform.annotations.filterIsInstance<Argument>().single().value
}

fun Module.getAndCacheLanguageLevelByDependencies(): LanguageVersion {
    val languageLevel = getLibraryLanguageLevel(
            this,
            null,
            KotlinFacetSettingsProvider.getInstance(project).getSettings(this).targetPlatformKind
    )

    // Preserve inferred version in facet/project settings
    val facetSettings = KotlinFacetSettingsProvider.getInstance(project).getSettings(this)
    if (facetSettings.useProjectSettings) {
        with(KotlinCommonCompilerArgumentsHolder.getInstance(project).settings) {
            if (languageVersion == null) {
                languageVersion = languageLevel.versionString
            }
            if (apiVersion == null) {
                apiVersion = languageLevel.versionString
            }
        }
    }
    else {
        with(facetSettings) {
            if (this.languageLevel == null) {
                this.languageLevel = languageLevel
            }
            if (this.apiLevel == null) {
                this.apiLevel = languageLevel
            }
        }
    }

    return languageLevel
}

fun Project.getLanguageVersionSettings(contextModule: Module? = null): LanguageVersionSettings {
    val arguments = KotlinCommonCompilerArgumentsHolder.getInstance(this).settings
    val languageVersion =
            LanguageVersion.fromVersionString(arguments.languageVersion)
            ?: contextModule?.getAndCacheLanguageLevelByDependencies()
            ?: LanguageVersion.LATEST
    val apiVersion = ApiVersion.createByLanguageVersion(LanguageVersion.fromVersionString(arguments.apiVersion) ?: languageVersion)
    val compilerSettings = KotlinCompilerSettings.getInstance(this).settings
    val extraLanguageFeatures = getExtraLanguageFeatures(
            TargetPlatformKind.Common,
            CoroutineSupport.byCompilerArguments(KotlinCommonCompilerArgumentsHolder.getInstance(this).settings),
            compilerSettings,
            null
    )
    return LanguageVersionSettingsImpl(
            languageVersion,
            apiVersion,
            arguments.skipMetadataVersionCheck,
            extraLanguageFeatures
    )
}

val Module.languageVersionSettings: LanguageVersionSettings
    get() {
        val facetSettings = KotlinFacetSettingsProvider.getInstance(project).getSettings(this)
        if (facetSettings.useProjectSettings) return project.getLanguageVersionSettings(this)
        val languageVersion = facetSettings.languageLevel ?: getAndCacheLanguageLevelByDependencies()
        val apiVersion = facetSettings.apiLevel ?: languageVersion

        val extraLanguageFeatures = getExtraLanguageFeatures(
                facetSettings.targetPlatformKind ?: TargetPlatformKind.Common,
                facetSettings.coroutineSupport,
                facetSettings.compilerSettings,
                this
        )

        return LanguageVersionSettingsImpl(
                languageVersion,
                ApiVersion.createByLanguageVersion(apiVersion),
                facetSettings.skipMetadataVersionCheck,
                extraLanguageFeatures
        )
    }

val Module.targetPlatform: TargetPlatformKind<*>?
    get() = KotlinFacetSettingsProvider.getInstance(project).getSettings(this).targetPlatformKind

private val Module.implementsCommonModule: Boolean
    get() = targetPlatform != TargetPlatformKind.Common
            && ModuleRootManager.getInstance(this).dependencies.any { it.targetPlatform == TargetPlatformKind.Common }

private fun getExtraLanguageFeatures(
        targetPlatformKind: TargetPlatformKind<*>,
        coroutineSupport: CoroutineSupport,
        compilerSettings: CompilerSettings?,
        module: Module?
): Map<LanguageFeature, LanguageFeature.State> {
    return mutableMapOf<LanguageFeature, LanguageFeature.State>().apply {
        when (coroutineSupport) {
            CoroutineSupport.ENABLED -> put(LanguageFeature.Coroutines, LanguageFeature.State.ENABLED)
            CoroutineSupport.ENABLED_WITH_WARNING -> put(LanguageFeature.Coroutines, LanguageFeature.State.ENABLED_WITH_WARNING)
            CoroutineSupport.DISABLED -> put(LanguageFeature.Coroutines, LanguageFeature.State.ENABLED_WITH_ERROR)
        }
        if (targetPlatformKind == TargetPlatformKind.Common ||
            // TODO: this is a dirty hack, parse arguments correctly here
            compilerSettings?.additionalArguments?.contains(multiPlatformProjectsArg) == true ||
            (module != null && module.implementsCommonModule)) {
            put(LanguageFeature.MultiPlatformProjects, LanguageFeature.State.ENABLED)
        }
    }
}

val KtElement.languageVersionSettings: LanguageVersionSettings
    get() {
        if (ServiceManager.getService(containingKtFile.project, ProjectFileIndex::class.java) == null) {
            return LanguageVersionSettingsImpl.DEFAULT
        }
        return ModuleUtilCore.findModuleForPsiElement(this)?.languageVersionSettings ?: LanguageVersionSettingsImpl.DEFAULT
    }

val KtElement.jvmTarget: JvmTarget
    get() = ModuleUtilCore.findModuleForPsiElement(this)?.targetPlatform?.version as? JvmTarget ?: JvmTarget.DEFAULT
