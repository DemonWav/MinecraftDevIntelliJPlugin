/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.bukkit.creator

import com.demonwav.mcdev.creator.ProjectConfig
import com.demonwav.mcdev.creator.buildsystem.BuildSystem
import com.demonwav.mcdev.creator.buildsystem.BuildSystemType
import com.demonwav.mcdev.creator.buildsystem.maven.BasicMavenStep
import com.demonwav.mcdev.platform.BaseTemplate
import com.demonwav.mcdev.platform.bukkit.BukkitLikeConfiguration
import com.demonwav.mcdev.platform.bukkit.BukkitModuleType
import com.demonwav.mcdev.platform.bukkit.data.LoadOrder
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.BUKKIT_BUILD_GRADLE_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.BUKKIT_GRADLE_PROPERTIES_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.BUKKIT_MAIN_CLASS_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.BUKKIT_PLUGIN_YML_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.BUKKIT_POM_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.BUKKIT_SETTINGS_GRADLE_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.BUKKIT_SUBMODULE_BUILD_GRADLE_TEMPLATE
import com.demonwav.mcdev.util.MinecraftTemplates.Companion.BUKKIT_SUBMODULE_POM_TEMPLATE
import com.demonwav.mcdev.util.SemanticVersion
import com.intellij.openapi.project.Project

object BukkitTemplate : BaseTemplate() {

    fun applyMainClass(
        project: Project,
        packageName: String,
        className: String
    ): String {
        val props = mapOf(
            "PACKAGE" to packageName,
            "CLASS_NAME" to className
        )

        return project.applyTemplate(BUKKIT_MAIN_CLASS_TEMPLATE, props)
    }

    fun applyPom(project: Project): String {
        return project.applyTemplate(BUKKIT_POM_TEMPLATE, BasicMavenStep.pluginVersions)
    }

    fun applySubPom(project: Project): String {
        return project.applyTemplate(BUKKIT_SUBMODULE_POM_TEMPLATE, BasicMavenStep.pluginVersions)
    }

    fun applyBuildGradle(project: Project, buildSystem: BuildSystem): String {
        val props = mapOf(
            "GROUP_ID" to buildSystem.groupId,
            "PLUGIN_VERSION" to buildSystem.version
        )

        return project.applyTemplate(BUKKIT_BUILD_GRADLE_TEMPLATE, props)
    }

    fun applyGradleProp(project: Project): String {
        return project.applyTemplate(BUKKIT_GRADLE_PROPERTIES_TEMPLATE)
    }

    fun applySettingsGradle(project: Project, artifactId: String): String {
        val props = mapOf(
            "ARTIFACT_ID" to artifactId
        )

        return project.applyTemplate(BUKKIT_SETTINGS_GRADLE_TEMPLATE, props)
    }

    fun applySubBuildGradle(project: Project, buildSystem: BuildSystem): String {
        val props = mapOf(
            "COMMON_PROJECT_NAME" to buildSystem.commonModuleName
        )

        return project.applyTemplate(BUKKIT_SUBMODULE_BUILD_GRADLE_TEMPLATE, props)
    }

    fun applyPluginYml(
        project: Project,
        config: BukkitProjectConfig,
        buildSystem: BuildSystem
    ): String {
        fun bukkitDeps(props: MutableMap<String, String>, configuration: BukkitLikeConfiguration) {
            if (configuration.hasDependencies()) {
                props["DEPEND"] = configuration.dependencies.toString()
            }
            if (configuration.hasSoftDependencies()) {
                props["SOFT_DEPEND"] = configuration.softDependencies.toString()
            }
        }

        val props = bukkitMain(buildSystem.type, config)

        if (config.hasPrefix()) {
            props["PREFIX"] = config.prefix ?: throw IllegalStateException("prefix is null when not blank")
        }

        if (config.loadOrder != LoadOrder.POSTWORLD) {
            props["LOAD"] = LoadOrder.STARTUP.name
        }

        if (config.hasLoadBefore()) {
            props["LOAD_BEFORE"] = config.loadBefore.toString()
        }

        bukkitDeps(props, config)

        if (config.hasAuthors()) {
            props["AUTHOR_LIST"] = config.authors.toString()
        }

        if (config.hasDescription()) {
            props["DESCRIPTION"] = config.description
                ?: throw IllegalStateException("description is null when not blank")
        }

        if (config.hasWebsite()) {
            props["WEBSITE"] = config.website ?: throw IllegalStateException("website is null when not blank")
        }

        // Plugins targeting 1.13 or newer need an explicit api declaration flag
        // This is the major and minor version separated by a dot without the patch version. ex: 1.15 even for 1.15.2
        config.minecraftVersion?.let { mcVersion ->
            val semVer = SemanticVersion.parse(mcVersion)
            if (semVer >= BukkitModuleType.API_TAG_VERSION) {
                props["API_VERSION"] = semVer.take(2).toString()
            }
        }

        return project.applyTemplate(BUKKIT_PLUGIN_YML_TEMPLATE, props)
    }

    fun <C> bukkitMain(type: BuildSystemType, config: C): MutableMap<String, String>
        where C : ProjectConfig,
              C : BukkitLikeConfiguration {
        val version = when (type) {
            BuildSystemType.GRADLE -> "\${version}"
            BuildSystemType.MAVEN -> "\${project.version}"
        }

        return mutableMapOf(
            "MAIN" to config.mainClass,
            "VERSION" to version,
            "NAME" to config.pluginName
        )
    }
}
