/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.fabric.creator

import com.demonwav.mcdev.creator.buildsystem.BuildSystem
import com.demonwav.mcdev.platform.BaseTemplate
import com.demonwav.mcdev.platform.forge.inspections.sideonly.Side
import com.demonwav.mcdev.util.License
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.FABRIC_BUILD_GRADLE_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.FABRIC_GRADLE_PROPERTIES_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.FABRIC_MIXINS_JSON_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.FABRIC_MOD_JSON_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.FABRIC_SETTINGS_GRADLE_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.FABRIC_SUBMODULE_BUILD_GRADLE_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.FABRIC_SUBMODULE_GRADLE_PROPERTIES_TEMPLATE
import com.demonwav.mcdev.util.toPackageName
import com.intellij.openapi.project.Project

object FabricTemplate : BaseTemplate() {

    private fun Project.applyGradleTemplate(
        templateName: String,
        buildSystem: BuildSystem,
        config: FabricProjectConfig
    ): String {
        val props = mutableMapOf(
            "GROUP_ID" to buildSystem.groupId,
            "ARTIFACT_ID" to buildSystem.artifactId,
            "VERSION" to buildSystem.version,
            "MC_VERSION" to config.mcVersion,
            "YARN_MAPPINGS" to config.yarnVersion,
            "LOADER_VERSION" to config.loaderVersion.toString(),
            "LOOM_VERSION" to config.loomVersion.toString()
        )
        config.yarnClassifier?.let {
            props["YARN_CLASSIFIER"] = it
        }
        config.apiVersion?.let {
            props["API_VERSION"] = it.toString()
        }
        config.apiMavenLocation?.let {
            props["API_MAVEN_LOCATION"] = it
        }

        return applyTemplate(templateName, props)
    }

    fun applyBuildGradle(
        project: Project,
        buildSystem: BuildSystem,
        config: FabricProjectConfig
    ): String {
        return project.applyGradleTemplate(FABRIC_BUILD_GRADLE_TEMPLATE, buildSystem, config)
    }

    fun applyMultiModuleBuildGradle(
        project: Project,
        buildSystem: BuildSystem,
        config: FabricProjectConfig
    ): String {
        return project.applyGradleTemplate(FABRIC_SUBMODULE_BUILD_GRADLE_TEMPLATE, buildSystem, config)
    }

    fun applySettingsGradle(
        project: Project,
        buildSystem: BuildSystem,
        config: FabricProjectConfig
    ): String {
        return project.applyGradleTemplate(FABRIC_SETTINGS_GRADLE_TEMPLATE, buildSystem, config)
    }

    fun applyGradleProp(
        project: Project,
        buildSystem: BuildSystem,
        config: FabricProjectConfig
    ): String {
        return project.applyGradleTemplate(FABRIC_GRADLE_PROPERTIES_TEMPLATE, buildSystem, config)
    }

    fun applyMultiModuleGradleProp(
        project: Project,
        buildSystem: BuildSystem,
        config: FabricProjectConfig
    ): String {
        return project.applyGradleTemplate(FABRIC_SUBMODULE_GRADLE_PROPERTIES_TEMPLATE, buildSystem, config)
    }

    fun applyFabricModJsonTemplate(
        project: Project,
        buildSystem: BuildSystem,
        config: FabricProjectConfig
    ): String {
        val props = mutableMapOf(
            "ARTIFACT_ID" to buildSystem.artifactId,
            "MOD_NAME" to config.pluginName,
            "MOD_DESCRIPTION" to (config.description ?: ""),
            "MOD_ENVIRONMENT" to when (config.environment) {
                Side.CLIENT -> "client"
                Side.SERVER -> "server"
                else -> "*"
            },
            "LOADER_VERSION" to config.loaderVersion.toString(),
            "MC_VERSION" to config.semanticMcVersion.toString(),
            "LICENSE" to ((config.license ?: License.ALL_RIGHTS_RESERVED).id)
        )
        config.apiVersion?.let {
            props["API_VERSION"] = it.toString()
        }
        if (config.mixins) {
            props["MIXINS"] = "true"
        }

        return project.applyTemplate(FABRIC_MOD_JSON_TEMPLATE, props)
    }

    fun applyMixinConfigTemplate(
        project: Project,
        buildSystem: BuildSystem
    ): String {
        val packageName = "${buildSystem.groupId.toPackageName()}.${buildSystem.artifactId.toPackageName()}.mixin"
        val props = mapOf(
            "PACKAGE_NAME" to packageName
        )
        return project.applyTemplate(FABRIC_MIXINS_JSON_TEMPLATE, props)
    }
}
