/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.forge.creator

import com.demonwav.mcdev.creator.BaseProjectCreator
import com.demonwav.mcdev.creator.BasicJavaClassStep
import com.demonwav.mcdev.creator.CreatorStep
import com.demonwav.mcdev.creator.LicenseStep
import com.demonwav.mcdev.creator.buildsystem.BuildSystem
import com.demonwav.mcdev.creator.buildsystem.gradle.BasicGradleFinalizerStep
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleBuildSystem
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleFiles
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleGitignoreStep
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleWrapperStep
import com.demonwav.mcdev.creator.buildsystem.gradle.SimpleGradleSetupStep
import com.demonwav.mcdev.platform.forge.util.ForgeConstants
import com.demonwav.mcdev.platform.forge.util.ForgePackDescriptor
import com.demonwav.mcdev.util.SemanticVersion
import com.demonwav.mcdev.util.runGradleTaskAndWait
import com.demonwav.mcdev.util.runWriteTask
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

class Fg2ProjectCreator(
    private val rootDirectory: Path,
    private val rootModule: Module,
    private val buildSystem: GradleBuildSystem,
    private val config: ForgeProjectConfig,
    private val mcVersion: SemanticVersion
) : BaseProjectCreator(rootModule, buildSystem) {

    private fun setupMainClassStep(): BasicJavaClassStep {
        return createJavaClassStep(config.mainClass) { packageName, className ->
            Fg2Template.applyMainClass(project, buildSystem, config, packageName, className)
        }
    }

    override fun getSingleModuleSteps(): Iterable<CreatorStep> {
        val buildText = Fg2Template.applyBuildGradle(project, buildSystem, mcVersion)
        val propText = Fg2Template.applyGradleProp(project, config)
        val settingsText = Fg2Template.applySettingsGradle(project, buildSystem.artifactId)
        val files = GradleFiles(buildText, propText, settingsText)

        return listOf(
            SimpleGradleSetupStep(
                project,
                rootDirectory,
                buildSystem,
                files
            ),
            setupMainClassStep(),
            GradleWrapperStep(project, rootDirectory, buildSystem),
            McmodInfoStep(project, buildSystem, config),
            SetupDecompWorkspaceStep(project, rootDirectory),
            GradleGitignoreStep(project, rootDirectory),
            BasicGradleFinalizerStep(rootModule, rootDirectory, buildSystem),
            ForgeRunConfigsStep(buildSystem, rootDirectory, config, CreatedModuleType.SINGLE)
        )
    }

    override fun getMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val buildText = Fg2Template.applySubBuildGradle(project, buildSystem, mcVersion)
        val propText = Fg2Template.applyGradleProp(project, config)
        val files = GradleFiles(buildText, propText, null)

        return listOf(
            SimpleGradleSetupStep(
                project,
                rootDirectory,
                buildSystem,
                files
            ),
            setupMainClassStep(),
            McmodInfoStep(project, buildSystem, config),
            SetupDecompWorkspaceStep(project, rootDirectory),
            ForgeRunConfigsStep(buildSystem, projectBaseDir, config, CreatedModuleType.MULTI)
        )
    }

    companion object {
        val FG_WRAPPER_VERSION = SemanticVersion.release(4, 10, 3)
    }
}

open class Fg3ProjectCreator(
    protected val rootDirectory: Path,
    protected val rootModule: Module,
    protected val buildSystem: GradleBuildSystem,
    protected val config: ForgeProjectConfig
) : BaseProjectCreator(rootModule, buildSystem) {

    private fun setupMainClassStep(): BasicJavaClassStep {
        return createJavaClassStep(config.mainClass) { packageName, className ->
            Fg3Template.applyMainClass(project, buildSystem, config, packageName, className)
        }
    }

    protected fun transformModName(modName: String?): String {
        modName ?: return "examplemod"
        return modName.toLowerCase().replace(" ", "")
    }

    protected fun createGradleFiles(hasData: Boolean): GradleFiles<String> {
        val modName = transformModName(config.pluginName)
        val buildText = Fg3Template.applyBuildGradle(project, buildSystem, config, modName, hasData)
        val propText = Fg3Template.applyGradleProp(project)
        val settingsText = Fg3Template.applySettingsGradle(project, buildSystem.artifactId)
        return GradleFiles(buildText, propText, settingsText)
    }

    override fun getSingleModuleSteps(): Iterable<CreatorStep> {
        val files = createGradleFiles(hasData = true)
        val steps = mutableListOf(
            SimpleGradleSetupStep(
                project,
                rootDirectory,
                buildSystem,
                files
            ),
            setupMainClassStep(),
            GradleWrapperStep(project, rootDirectory, buildSystem),
            Fg3ProjectFilesStep(project, buildSystem, config),
            Fg3CompileJavaStep(project, rootDirectory),
            GradleGitignoreStep(project, rootDirectory),
            LicenseStep(project, rootDirectory, config.license, config.authors.joinToString(", ")),
            BasicGradleFinalizerStep(rootModule, rootDirectory, buildSystem),
            ForgeRunConfigsStep(buildSystem, rootDirectory, config, CreatedModuleType.SINGLE)
        )

        if (config.mixins) {
            steps += MixinConfigStep(project, buildSystem)
        }

        return steps
    }

    override fun getMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val modName = transformModName(config.pluginName)
        val buildText = Fg3Template.applySubBuildGradle(project, buildSystem, config, modName, hasData = true)
        val files = GradleFiles(buildText, null, null)

        val steps = mutableListOf(
            SimpleGradleSetupStep(
                project,
                rootDirectory,
                buildSystem,
                files
            ),
            setupMainClassStep(),
            Fg3ProjectFilesStep(project, buildSystem, config),
            Fg3CompileJavaStep(project, rootDirectory),
            LicenseStep(project, rootDirectory, config.license, config.authors.joinToString(", ")),
            ForgeRunConfigsStep(buildSystem, projectBaseDir, config, CreatedModuleType.MULTI)
        )

        if (config.mixins) {
            steps += MixinConfigStep(project, buildSystem)
        }

        return steps
    }

    companion object {
        val FG4_WRAPPER_VERSION = SemanticVersion.release(6, 8, 1)
    }
}

class Fg3Mc112ProjectCreator(
    rootDirectory: Path,
    rootModule: Module,
    buildSystem: GradleBuildSystem,
    config: ForgeProjectConfig
) : Fg3ProjectCreator(rootDirectory, rootModule, buildSystem, config) {

    private fun setupMainClassStep(): BasicJavaClassStep {
        return createJavaClassStep(config.mainClass) { packageName, className ->
            Fg2Template.applyMainClass(project, buildSystem, config, packageName, className)
        }
    }

    override fun getSingleModuleSteps(): Iterable<CreatorStep> {
        val files = createGradleFiles(hasData = false)

        return listOf(
            SimpleGradleSetupStep(
                project,
                rootDirectory,
                buildSystem,
                files
            ),
            setupMainClassStep(),
            GradleWrapperStep(project, rootDirectory, buildSystem),
            McmodInfoStep(project, buildSystem, config),
            Fg3CompileJavaStep(project, rootDirectory),
            GradleGitignoreStep(project, rootDirectory),
            BasicGradleFinalizerStep(rootModule, rootDirectory, buildSystem),
            ForgeRunConfigsStep(buildSystem, rootDirectory, config, CreatedModuleType.SINGLE)
        )
    }

    override fun getMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val modName = transformModName(config.pluginName)
        val buildText = Fg3Template.applySubBuildGradle(project, buildSystem, config, modName, hasData = false)
        val files = GradleFiles(buildText, null, null)

        return listOf(
            SimpleGradleSetupStep(
                project,
                rootDirectory,
                buildSystem,
                files
            ),
            setupMainClassStep(),
            McmodInfoStep(project, buildSystem, config),
            Fg3CompileJavaStep(project, rootDirectory),
            ForgeRunConfigsStep(buildSystem, projectBaseDir, config, CreatedModuleType.MULTI)
        )
    }
}

class SetupDecompWorkspaceStep(
    private val project: Project,
    private val rootDirectory: Path
) : CreatorStep {

    override fun runStep(indicator: ProgressIndicator) {
        indicator.text = "Setting up project"
        indicator.text2 = "Running Gradle task: 'setupDecompWorkspace'"
        runGradleTaskAndWait(project, rootDirectory) { settings ->
            settings.taskNames = listOf("setupDecompWorkspace")
            settings.vmOptions = "-Xmx2G"
        }
        indicator.text2 = null
    }
}

class McmodInfoStep(
    private val project: Project,
    private val buildSystem: BuildSystem,
    private val config: ForgeProjectConfig
) : CreatorStep {

    override fun runStep(indicator: ProgressIndicator) {
        val text = Fg2Template.applyMcmodInfo(project, buildSystem, config)
        val dir = buildSystem.dirsOrError.resourceDirectory
        runWriteTask {
            CreatorStep.writeTextToFile(project, dir, ForgeConstants.MCMOD_INFO, text)
        }
    }
}

class Fg3ProjectFilesStep(
    private val project: Project,
    private val buildSystem: BuildSystem,
    private val config: ForgeProjectConfig
) : CreatorStep {

    override fun runStep(indicator: ProgressIndicator) {
        val modsTomlText = Fg3Template.applyModsToml(project, buildSystem, config)
        val packDescriptor = ForgePackDescriptor.forMcVersion(config.mcVersion) ?: ForgePackDescriptor.FORMAT_3
        val packMcmetaText = Fg3Template.applyPackMcmeta(project, buildSystem.artifactId, packDescriptor)
        val dir = buildSystem.dirsOrError.resourceDirectory
        runWriteTask {
            CreatorStep.writeTextToFile(project, dir, ForgeConstants.PACK_MCMETA, packMcmetaText)
            val meta = dir.resolve("META-INF")
            Files.createDirectories(meta)
            CreatorStep.writeTextToFile(project, meta, ForgeConstants.MODS_TOML, modsTomlText)
        }
    }
}

class Fg3CompileJavaStep(
    private val project: Project,
    private val rootDirectory: Path
) : CreatorStep {

    override fun runStep(indicator: ProgressIndicator) {
        indicator.text = "Setting up classpath"
        indicator.text2 = "Running Gradle task: 'compileJava'"
        runGradleTaskAndWait(project, rootDirectory) { settings ->
            settings.taskNames = listOf("compileJava")
        }
        indicator.text2 = null
    }
}

class MixinConfigStep(
    private val project: Project,
    private val buildSystem: BuildSystem
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        val text = Fg3Template.applyMixinConfigTemplate(project, buildSystem)
        val dir = buildSystem.dirsOrError.resourceDirectory
        runWriteTask {
            CreatorStep.writeTextToFile(project, dir, "${buildSystem.artifactId}.mixins.json", text)
        }
    }
}

enum class CreatedModuleType {
    SINGLE, MULTI
}

class ForgeRunConfigsStep(
    private val buildSystem: BuildSystem,
    private val rootDirectory: Path,
    private val config: ForgeProjectConfig,
    private val createdModuleType: CreatedModuleType
) : CreatorStep {

    override fun runStep(indicator: ProgressIndicator) {
        val gradleDir = rootDirectory.resolve(".gradle")
        Files.createDirectories(gradleDir)
        val hello = gradleDir.resolve(HELLO)

        val task = if (createdModuleType == CreatedModuleType.MULTI) {
            ":${buildSystem.artifactId}:genIntellijRuns"
        } else {
            "genIntellijRuns"
        }

        // We don't use `rootModule.name` here because Gradle will change the name of the module to match
        // what was set as the artifactId once it imports the project
        val moduleName = if (createdModuleType == CreatedModuleType.MULTI) {
            "${buildSystem.parentOrError.artifactId}.${buildSystem.artifactId}"
        } else {
            buildSystem.artifactId
        }

        val fileContents = moduleName + "\n" +
            config.mcVersion + "\n" +
            config.forgeVersion + "\n" +
            task

        Files.write(hello, fileContents.toByteArray(Charsets.UTF_8), CREATE, TRUNCATE_EXISTING, WRITE)
    }

    companion object {
        const val HELLO = ".hello_from_mcdev"
    }
}
