/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.creator.buildsystem.gradle

import com.demonwav.mcdev.creator.CreateDirectoriesStep
import com.demonwav.mcdev.creator.CreatorStep
import com.demonwav.mcdev.creator.ProjectCreator
import com.demonwav.mcdev.creator.buildsystem.BuildSystem
import com.demonwav.mcdev.creator.buildsystem.BuildSystemTemplate
import com.demonwav.mcdev.creator.buildsystem.BuildSystemType
import com.demonwav.mcdev.platform.PlatformType
import com.demonwav.mcdev.util.SemanticVersion
import com.intellij.openapi.module.Module
import java.nio.file.Path
import java.util.Locale

class GradleBuildSystem(
    groupId: String,
    artifactId: String,
    version: String,
    override val parent: GradleBuildSystem? = null
) : BuildSystem(groupId, artifactId, version) {

    override val type = BuildSystemType.GRADLE

    var gradleVersion: SemanticVersion = DEFAULT_WRAPPER_VERSION

    override fun createSub(artifactId: String): BuildSystem {
        return GradleBuildSystem(this.groupId, artifactId, this.version, this)
    }

    override fun multiModuleBaseSteps(
        module: Module,
        types: List<PlatformType>,
        rootDirectory: Path
    ): Iterable<CreatorStep> {
        val project = module.project

        val baseName = artifactId.toLowerCase(Locale.ENGLISH)
        val names = mutableListOf("$baseName-common")
        names += types.map { "$baseName-${it.name.toLowerCase(Locale.ENGLISH)}" }

        val buildText = BuildSystemTemplate.applyBuildGradle(project, this)
        val propText = BuildSystemTemplate.applyGradleProp(project)
        val settingsText = BuildSystemTemplate.applySettingsGradle(project, this, names)
        val files = GradleFiles(buildText, propText, settingsText)

        return listOf(
            GradleSetupStep(project, rootDirectory, this, files),
            GradleWrapperStep(project, rootDirectory, this)
        )
    }

    override fun multiModuleBaseFinalizer(module: Module, rootDirectory: Path): Iterable<CreatorStep> {
        return listOf(
            GradleGitignoreStep(module.project, rootDirectory),
            BasicGradleFinalizerStep(module, rootDirectory, this)
        )
    }

    override fun multiModuleCommonSteps(module: Module, rootDirectory: Path): Iterable<CreatorStep> {
        return listOf(CreateDirectoriesStep(this, rootDirectory))
    }

    override fun buildCreator(obj: Any, rootDirectory: Path, module: Module): ProjectCreator {
        if (obj !is GradleCreator) {
            throw IllegalStateException("Cannot create a Gradle module from ${obj.javaClass.name}")
        }
        return obj.buildGradleCreator(rootDirectory, module, this)
    }

    override fun configure(list: Collection<Any>, rootDirectory: Path) {
        for (obj in list) {
            if (obj is GradleCreator) {
                obj.configureRootGradle(rootDirectory, this)
            }
        }
    }

    companion object {
        val DEFAULT_WRAPPER_VERSION = SemanticVersion.release(7, 0, 2)
    }
}

/**
 * A [ProjectConfig][com.demonwav.mcdev.creator.ProjectConfig] must implement this interface in order to support
 * creating `Gradle` projects.
 */
interface GradleCreator {
    fun buildGradleCreator(rootDirectory: Path, module: Module, buildSystem: GradleBuildSystem): ProjectCreator

    /**
     * This method allows extra configuration of the root [GradleBuildSystem] before any [CreatorStep]s have been
     * created or executed. Not typically needed.
     */
    fun configureRootGradle(rootDirectory: Path, buildSystem: GradleBuildSystem) {}
}
