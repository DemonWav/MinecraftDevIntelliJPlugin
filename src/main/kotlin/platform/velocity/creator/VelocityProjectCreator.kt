/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.velocity.creator

import com.demonwav.mcdev.creator.BaseProjectCreator
import com.demonwav.mcdev.creator.BasicJavaClassStep
import com.demonwav.mcdev.creator.CreateDirectoriesStep
import com.demonwav.mcdev.creator.CreatorStep
import com.demonwav.mcdev.creator.buildsystem.BuildDependency
import com.demonwav.mcdev.creator.buildsystem.BuildRepository
import com.demonwav.mcdev.creator.buildsystem.BuildSystem
import com.demonwav.mcdev.creator.buildsystem.gradle.AddGradlePluginStep
import com.demonwav.mcdev.creator.buildsystem.gradle.BasicGradleFinalizerStep
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleBuildSystem
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleFiles
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleGitignoreStep
import com.demonwav.mcdev.creator.buildsystem.gradle.GradlePlugin
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleSetupStep
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleWrapperStep
import com.demonwav.mcdev.creator.buildsystem.maven.BasicMavenFinalizerStep
import com.demonwav.mcdev.creator.buildsystem.maven.BasicMavenStep
import com.demonwav.mcdev.creator.buildsystem.maven.CommonModuleDependencyStep
import com.demonwav.mcdev.creator.buildsystem.maven.MavenBuildSystem
import com.demonwav.mcdev.creator.buildsystem.maven.MavenGitignoreStep
import com.demonwav.mcdev.util.SemanticVersion
import com.demonwav.mcdev.util.runWriteAction
import com.demonwav.mcdev.util.runWriteTaskInSmartMode
import com.demonwav.mcdev.util.virtualFileOrError
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

sealed class VelocityProjectCreator<T : BuildSystem>(
    protected val rootDirectory: Path,
    protected val rootModule: Module,
    protected val buildSystem: T,
    protected val config: VelocityProjectConfig
) : BaseProjectCreator(rootModule, buildSystem) {

    protected fun setupDependencyStep(): VelocityDependenciesSetup {
        val velocityApiVersion = config.velocityApiVersion
        return VelocityDependenciesSetup(buildSystem, velocityApiVersion)
    }

    protected fun setupMainClassSteps(): Pair<CreatorStep, CreatorStep> {
        val mainClassStep = createJavaClassStep(config.mainClass) { packageName, className ->
            val version = SemanticVersion.parse(config.velocityApiVersion)
            VelocityTemplate.applyMainClass(project, packageName, className, config.hasDependencies(), version)
        }

        return mainClassStep to VelocityMainClassModifyStep(project, buildSystem, config.mainClass, config)
    }
}

class VelocityMavenCreator(
    rootDirectory: Path,
    rootModule: Module,
    buildSystem: MavenBuildSystem,
    config: VelocityProjectConfig
) : VelocityProjectCreator<MavenBuildSystem>(rootDirectory, rootModule, buildSystem, config) {
    override fun getSingleModuleSteps(): Iterable<CreatorStep> {
        val (mainClassStep, modifyStep) = setupMainClassSteps()

        val pomText = VelocityTemplate.applyPom(project)

        return listOf(
            setupDependencyStep(),
            BasicMavenStep(project, rootDirectory, buildSystem, config, pomText),
            mainClassStep,
            modifyStep,
            MavenGitignoreStep(project, rootDirectory),
            BasicMavenFinalizerStep(rootModule, rootDirectory)
        )
    }

    override fun getMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val depStep = setupDependencyStep()
        val commonDepStep = CommonModuleDependencyStep(buildSystem)
        val (mainClassStep, modifyStep) = setupMainClassSteps()

        val pomText = VelocityTemplate.applySubPom(project)
        val mavenStep = BasicMavenStep(
            project,
            rootDirectory,
            buildSystem,
            config,
            pomText,
            listOf(
                BasicMavenStep.setupDirs(),
                BasicMavenStep.setupSubCore(buildSystem.parentOrError.artifactId),
                BasicMavenStep.setupSubName(config.type),
                BasicMavenStep.setupInfo(),
                BasicMavenStep.setupDependencies()
            )
        )
        return listOf(depStep, commonDepStep, mavenStep, mainClassStep, modifyStep)
    }
}

class VelocityGradleCreator(
    rootDirectory: Path,
    rootModule: Module,
    buildSystem: GradleBuildSystem,
    config: VelocityProjectConfig
) : VelocityProjectCreator<GradleBuildSystem>(rootDirectory, rootModule, buildSystem, config) {

    private val ideaExtPlugin = GradlePlugin("org.jetbrains.gradle.plugin.idea-ext", "1.0.1")

    override fun getSingleModuleSteps(): Iterable<CreatorStep> {
        val (mainClassStep, modifyStep) = setupMainClassSteps()

        val buildText = VelocityTemplate.applyBuildGradle(project, buildSystem)
        val propText = VelocityTemplate.applyGradleProp(project)
        val settingsText = VelocityTemplate.applySettingsGradle(project, buildSystem.artifactId)
        val files = GradleFiles(buildText, propText, settingsText)

        return listOf(
            setupDependencyStep(),
            CreateDirectoriesStep(buildSystem, rootDirectory),
            GradleSetupStep(project, rootDirectory, buildSystem, files),
            AddGradlePluginStep(project, rootDirectory, listOf(ideaExtPlugin)),
            mainClassStep,
            modifyStep,
            buildConstantsStep(),
            GradleWrapperStep(project, rootDirectory, buildSystem),
            GradleGitignoreStep(project, rootDirectory),
            BasicGradleFinalizerStep(rootModule, rootDirectory, buildSystem)
        )
    }

    override fun getMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val (mainClassStep, modifyStep) = setupMainClassSteps()

        val buildText = VelocityTemplate.applySubBuildGradle(project, buildSystem)
        val files = GradleFiles(buildText, null, null)

        return listOf(
            setupDependencyStep(),
            CreateDirectoriesStep(buildSystem, rootDirectory),
            GradleSetupStep(project, rootDirectory, buildSystem, files),
            AddGradlePluginStep(project, projectBaseDir, listOf(ideaExtPlugin)),
            mainClassStep,
            modifyStep,
            buildConstantsStep()
        )
    }

    private fun buildConstantsStep(): BasicJavaClassStep {
        return BasicJavaClassStep(
            project,
            buildSystem,
            config.mainClass.replaceAfterLast('.', "BuildConstants"),
            VelocityTemplate.applyBuildConstants(project, config.mainClass.substringBeforeLast('.')),
            false
        ) { it.dirsOrError.sourceDirectory.resolveSibling("templates") }
    }
}

class VelocityMainClassModifyStep(
    private val project: Project,
    private val buildSystem: BuildSystem,
    private val classFullName: String,
    private val config: VelocityProjectConfig
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        val dirs = buildSystem.dirsOrError

        project.runWriteTaskInSmartMode {
            val classFile = dirs.sourceDirectory.resolve(Paths.get(classFullName.replace('.', '/') + ".java"))
            if (!Files.isRegularFile(classFile)) {
                throw IllegalStateException("$classFile is not an existing file")
            }

            val psiFile = PsiManager.getInstance(project).findFile(classFile.virtualFileOrError) as? PsiJavaFile
                ?: throw IllegalStateException("Failed to resolve PsiJavaFile for $classFile")
            val psiClass = psiFile.classes[0]
            val annotationBuilder = StringBuilder("@Plugin(")
            annotationBuilder + "\nid = ${literal(buildSystem.artifactId)}"
            annotationBuilder + ",\nname = ${literal(config.pluginName)}"

            if (buildSystem is GradleBuildSystem) {
                annotationBuilder + ",\nversion = BuildConstants.VERSION"
            } else {
                annotationBuilder + ",\nversion = \"${buildSystem.version}\""
            }

            if (config.hasDescription()) {
                annotationBuilder + ",\ndescription = ${literal(config.description)}"
            }

            if (config.hasWebsite()) {
                annotationBuilder + ",\nurl = ${literal(config.website)}"
            }

            if (config.hasAuthors()) {
                annotationBuilder + ",\nauthors = {${config.authors.joinToString(", ", transform = ::literal)}}"
            }

            if (config.hasDependencies()) {
                val deps = config.dependencies.joinToString(",\n") { "@Dependency(id = ${literal(it)})" }
                annotationBuilder + ",\ndependencies = {\n$deps\n}"
            }

            annotationBuilder + "\n)"
            val factory = JavaPsiFacade.getElementFactory(project)
            val pluginAnnotation = factory.createAnnotationFromText(annotationBuilder.toString(), null)

            psiFile.runWriteAction {
                psiClass.modifierList?.let { it.addBefore(pluginAnnotation, it.firstChild) }
                CodeStyleManager.getInstance(project).reformat(psiClass)
            }
        }
    }

    private fun literal(text: String?): String {
        if (text == null) {
            return "\"\""
        }
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"'
    }

    private operator fun StringBuilder.plus(text: String) = this.append(text)
}

class VelocityDependenciesSetup(
    private val buildSystem: BuildSystem,
    private val velocityApiVersion: String
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        buildSystem.repositories.add(
            BuildRepository(
                "velocitypowered-repo",
                "https://nexus.velocitypowered.com/repository/maven-public/"
            )
        )

        buildSystem.dependencies.add(
            BuildDependency(
                "com.velocitypowered",
                "velocity-api",
                velocityApiVersion,
                mavenScope = "provided",
                gradleConfiguration = "compileOnly"
            )
        )
        buildSystem.dependencies.add(
            BuildDependency(
                "com.velocitypowered",
                "velocity-api",
                velocityApiVersion,
                gradleConfiguration = "annotationProcessor"
            )
        )
    }
}
