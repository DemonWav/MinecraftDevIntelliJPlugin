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

import com.demonwav.mcdev.creator.CreatorStep
import com.demonwav.mcdev.creator.CreatorStep.Companion.writeText
import com.demonwav.mcdev.creator.buildsystem.BuildSystem
import com.demonwav.mcdev.creator.buildsystem.BuildSystemTemplate
import com.demonwav.mcdev.creator.buildsystem.BuildSystemType
import com.demonwav.mcdev.creator.buildsystem.DirectorySet
import com.demonwav.mcdev.util.ifNotEmpty
import com.demonwav.mcdev.util.invokeLater
import com.demonwav.mcdev.util.runGradleTaskAndWait
import com.demonwav.mcdev.util.runWriteAction
import com.demonwav.mcdev.util.runWriteTask
import com.demonwav.mcdev.util.virtualFileOrError
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.execution.RunManager
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.StatusBarEx
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.project.open.canLinkAndRefreshGradleProject
import org.jetbrains.plugins.gradle.service.project.open.linkAndRefreshGradleProject
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.groovy.GroovyLanguage
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression

class SimpleGradleSetupStep(
    private val project: Project,
    private val rootDirectory: Path,
    private val buildSystem: BuildSystem,
    private val gradleFiles: GradleFiles<String>,
    private val kotlinScript: Boolean = false
) : CreatorStep {

    override fun runStep(indicator: ProgressIndicator) {
        runWriteTask {
            if (project.isDisposed) {
                return@runWriteTask
            }

            buildSystem.directories =
                DirectorySet.create(rootDirectory)
            val (buildGradle, gradleProp, settingsGradle) = setupGradleFiles(
                rootDirectory,
                gradleFiles,
                kotlinScript
            )

            val psiManager = PsiManager.getInstance(project)
            writeText(
                buildGradle,
                gradleFiles.buildGradle,
                psiManager
            )
            if (gradleProp != null && gradleFiles.gradleProperties != null) {
                writeText(
                    gradleProp,
                    gradleFiles.gradleProperties,
                    psiManager
                )
            }
            if (settingsGradle != null && gradleFiles.settingsGradle != null) {
                writeText(
                    settingsGradle,
                    gradleFiles.settingsGradle,
                    psiManager
                )
            }
        }
    }
}

class GradleSetupStep(
    private val project: Project,
    private val rootDirectory: Path,
    private val buildSystem: BuildSystem,
    private val gradleFiles: GradleFiles<String>,
    private val kotlinScript: Boolean = false
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        val (_, gradleProp, settingsGradle) = setupGradleFiles(rootDirectory, gradleFiles, kotlinScript)

        runWriteTask {
            if (project.isDisposed) {
                return@runWriteTask
            }

            val buildGradlePsi = addBuildGradleDependencies(project, buildSystem, gradleFiles.buildGradle, kotlinScript)
            val psiManager = PsiManager.getInstance(project)
            psiManager.findDirectory(rootDirectory.virtualFileOrError)?.let { dir ->
                dir.findFile(buildGradlePsi.name)?.delete()
                val newFile = dir.add(buildGradlePsi) as? PsiFile ?: return@let
                ReformatCodeProcessor(newFile, false).run()
            }

            if (gradleProp != null && gradleFiles.gradleProperties != null) {
                writeText(gradleProp, gradleFiles.gradleProperties, psiManager)
            }
            if (settingsGradle != null && gradleFiles.settingsGradle != null) {
                writeText(settingsGradle, gradleFiles.settingsGradle, psiManager)
            }
        }
    }
}

data class GradleFiles<out T>(
    val buildGradle: T,
    val gradleProperties: T?,
    val settingsGradle: T?
)

fun setupGradleFiles(dir: Path, givenFiles: GradleFiles<String>, kotlinScript: Boolean = false): GradleFiles<Path> {
    return GradleFiles(
        dir.resolve(if (kotlinScript) "build.gradle.kts" else "build.gradle"),
        givenFiles.gradleProperties?.let { dir.resolve("gradle.properties") },
        givenFiles.settingsGradle?.let { dir.resolve(if (kotlinScript) "settings.gradle.kts" else "settings.gradle") },
    ).apply {
        Files.deleteIfExists(buildGradle)
        Files.createFile(buildGradle)
        gradleProperties?.let { Files.deleteIfExists(it); Files.createFile(it) }
        settingsGradle?.let { Files.deleteIfExists(it); Files.createFile(it) }
    }
}

fun addBuildGradleDependencies(
    project: Project,
    buildSystem: BuildSystem,
    text: String,
    kotlinScript: Boolean = false
): PsiFile {
    val file = PsiFileFactory.getInstance(project).createFileFromText(GroovyLanguage, text)
    return file.runWriteAction {
        val fileName = if (kotlinScript) "build.gradle.kts" else "build.gradle"
        file.name = fileName

        val groovyFile = file as GroovyFile

        buildSystem.repositories.asSequence()
            .filter { it.buildSystems.contains(BuildSystemType.GRADLE) }
            .map { "maven {name = '${it.id}'\nurl = '${it.url}'\n}" }
            .toList()
            .ifNotEmpty { reps -> appendExpressions(project, groovyFile, "repositories", reps) }

        buildSystem.dependencies.asSequence()
            .filter { it.gradleConfiguration != null }
            .map { "${it.gradleConfiguration} '${it.groupId}:${it.artifactId}:${it.version}'" }
            .toList()
            .ifNotEmpty { deps -> appendExpressions(project, groovyFile, "dependencies", deps) }

        return@runWriteAction file
    }
}

class AddGradlePluginStep(
    private val project: Project,
    private val rootDirectory: Path,
    private val plugins: Collection<GradlePlugin>,
    private val kotlinScript: Boolean = false
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        val fileName = if (kotlinScript) "build.gradle.kts" else "build.gradle"
        val virtualFile = rootDirectory.resolve(fileName).virtualFileOrError
        runWriteTask {
            if (project.isDisposed) {
                return@runWriteTask
            }

            val file = PsiManager.getInstance(project).findFile(virtualFile)
                ?: throw IllegalStateException("Could not find $fileName")
            file.runWriteAction {
                if (project.isDisposed) {
                    return@runWriteAction
                }

                val groovyFile = file as GroovyFile

                plugins.asSequence()
                    .map { plugin ->
                        buildString {
                            append("id \"${plugin.id}\"")
                            plugin.version?.let { append(" version \"$it\"") }
                            if (!plugin.apply) {
                                append(" apply false")
                            }
                        }
                    }
                    .toList()
                    .ifNotEmpty { plugins -> appendExpressions(project, groovyFile, "plugins", plugins) }

                ReformatCodeProcessor(file, false).run()
            }
        }
    }
}

private fun appendExpressions(
    project: Project,
    file: GroovyFile,
    name: String,
    expressions: Iterable<String>
) {
    // Get the block so we can start working with it
    val block = getClosableBlockByName(file, name)
        ?: throw IllegalStateException("Failed to parse build.gradle files")

    // Create a super expression with all the expressions tied together
    val expressionText = expressions.joinToString("\n")

    // We can't create each expression and add them to the file...that won't work. Groovy requires a new line
    // from one method call expression to another, and there's no way to (easily) put whitespace in Psi because Psi is
    // stupid. So instead we make the whole thing as one big clump and insert it into the block.
    val fakeFile = GroovyPsiElementFactory.getInstance(project).createGroovyFile(expressionText, false, null)
    val last = block.children.last()
    block.addBefore(fakeFile, last)
}

private fun getClosableBlockByName(element: PsiElement, name: String) =
    element.children.asSequence()
        .filter {
            // We want to find the child which has a GrReferenceExpression with the right name
            it.children.any { child -> child is GrReferenceExpression && child.text == name }
        }.map {
            // We want to find the grandchild which is a GrClosable block
            it.children.mapNotNull { child -> child as? GrClosableBlock }.firstOrNull()
        }.filterNotNull()
        .firstOrNull()

class BasicGradleFinalizerStep(
    private val module: Module,
    private val rootDirectory: Path,
    private val buildSystem: BuildSystem,
    private vararg val additionalRunTasks: String
) : CreatorStep {
    private val project
        get() = module.project

    override fun runStep(indicator: ProgressIndicator) {
        // Tell IntelliJ to import this project
        rootDirectory.virtualFileOrError.refresh(false, true)

        invokeLater(module.disposed) {
            val path = rootDirectory.toAbsolutePath().toString()
            if (canLinkAndRefreshGradleProject(path, project, false)) {
                linkAndRefreshGradleProject(path, project)
                showProgress(project)
            }
        }

        // Set up the run config
        // Get the gradle external task type, this is what sets it as a gradle task
        addRunTaskConfiguration("build")
        for (tasks in additionalRunTasks) {
            addRunTaskConfiguration(tasks)
        }
    }

    private fun addRunTaskConfiguration(task: String) {
        val gradleType = GradleExternalTaskConfigurationType.getInstance()

        val runManager = RunManager.getInstance(project)
        val runConfigName = buildSystem.artifactId + ' ' + task

        val runConfiguration = ExternalSystemRunConfiguration(
            GradleConstants.SYSTEM_ID,
            project,
            gradleType.configurationFactories[0],
            runConfigName
        )

        // Set relevant gradle values
        runConfiguration.settings.externalProjectPath = rootDirectory.toAbsolutePath().toString()
        runConfiguration.settings.executionName = runConfigName
        runConfiguration.settings.taskNames = listOf(task)

        runConfiguration.isAllowRunningInParallel = false

        val settings = runManager.createConfiguration(
            runConfiguration,
            GradleExternalTaskConfigurationType.getInstance().configurationFactories.first()
        )

        settings.isActivateToolWindowBeforeRun = true
        settings.storeInLocalWorkspace()

        runManager.addConfiguration(settings)
        if (runManager.selectedConfiguration == null) {
            runManager.selectedConfiguration = settings
        }
    }
}

class GradleWrapperStep(
    private val project: Project,
    private val rootDirectory: Path,
    private val buildSystem: GradleBuildSystem
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        val wrapperVersion = buildSystem.gradleVersion

        // Setup gradle wrapper
        // We'll write the properties file to ensure it sets up with the right version
        val wrapperDir = rootDirectory.resolve("gradle/wrapper")
        Files.createDirectories(wrapperDir)
        val wrapperProp = wrapperDir.resolve("gradle-wrapper.properties")

        val text = "distributionUrl=https\\://services.gradle.org/distributions/gradle-$wrapperVersion-bin.zip\n"

        Files.write(wrapperProp, text.toByteArray(Charsets.UTF_8), CREATE, WRITE, TRUNCATE_EXISTING)

        indicator.text = "Setting up Gradle Wrapper"
        indicator.text2 = "Running Gradle task: 'wrapper'"
        runGradleTaskAndWait(project, rootDirectory) { settings ->
            settings.taskNames = listOf("wrapper")
        }
        indicator.text2 = null
    }
}

// Show the background processes window for setup tasks
private fun showProgress(project: Project) {
    if (!UISettings.instance.showStatusBar || UISettings.instance.presentationMode) {
        return
    }

    val statusBar = WindowManager.getInstance().getStatusBar(project) as? StatusBarEx ?: return
    statusBar.isProcessWindowOpen = true
}

class GradleGitignoreStep(
    private val project: Project,
    private val rootDirectory: Path
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        val gitignoreFile = rootDirectory.resolve(".gitignore")

        val fileText = BuildSystemTemplate.applyGradleGitignore(project)

        Files.write(gitignoreFile, fileText.toByteArray(Charsets.UTF_8), CREATE, WRITE, TRUNCATE_EXISTING)
    }
}
