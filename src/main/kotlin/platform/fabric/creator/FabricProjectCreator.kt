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

import com.demonwav.mcdev.creator.BaseProjectCreator
import com.demonwav.mcdev.creator.CreatorStep
import com.demonwav.mcdev.creator.LicenseStep
import com.demonwav.mcdev.creator.PostMultiModuleAware
import com.demonwav.mcdev.creator.buildsystem.BuildSystem
import com.demonwav.mcdev.creator.buildsystem.gradle.BasicGradleFinalizerStep
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleBuildSystem
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleFiles
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleGitignoreStep
import com.demonwav.mcdev.creator.buildsystem.gradle.GradleWrapperStep
import com.demonwav.mcdev.creator.buildsystem.gradle.SimpleGradleSetupStep
import com.demonwav.mcdev.platform.fabric.EntryPoint
import com.demonwav.mcdev.platform.fabric.util.FabricConstants
import com.demonwav.mcdev.util.addAnnotation
import com.demonwav.mcdev.util.addImplements
import com.demonwav.mcdev.util.addMethod
import com.demonwav.mcdev.util.invokeLater
import com.demonwav.mcdev.util.runGradleTaskAndWait
import com.demonwav.mcdev.util.runWriteAction
import com.demonwav.mcdev.util.runWriteTask
import com.demonwav.mcdev.util.runWriteTaskInSmartMode
import com.demonwav.mcdev.util.virtualFile
import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.daemon.impl.quickfix.CreateFromUsageUtils
import com.intellij.codeInsight.generation.OverrideImplementUtil
import com.intellij.codeInsight.generation.PsiMethodMember
import com.intellij.ide.util.EditorHelper
import com.intellij.json.JsonLanguage
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.JavaDirectoryService
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.util.IncorrectOperationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class FabricProjectCreator(
    private val rootDirectory: Path,
    private val rootModule: Module,
    private val buildSystem: GradleBuildSystem,
    private val config: FabricProjectConfig
) : BaseProjectCreator(rootModule, buildSystem), PostMultiModuleAware {

    override fun getSingleModuleSteps(): Iterable<CreatorStep> {
        val buildText = FabricTemplate.applyBuildGradle(project, buildSystem, config)
        val propText = FabricTemplate.applyGradleProp(project, buildSystem, config)
        val settingsText = FabricTemplate.applySettingsGradle(project, buildSystem, config)
        val files = GradleFiles(buildText, propText, settingsText)

        val steps = mutableListOf(
            SimpleGradleSetupStep(project, rootDirectory, buildSystem, files),
            GradleWrapperStep(project, rootDirectory, buildSystem)
        )
        if (config.genSources) {
            steps += GenSourcesStep(project, rootDirectory)
        }
        steps += GradleGitignoreStep(project, rootDirectory)
        config.license?.let {
            steps += LicenseStep(project, rootDirectory, it, config.authors.joinToString(", "))
        }
        steps += BasicGradleFinalizerStep(rootModule, rootDirectory, buildSystem)
        if (config.mixins) {
            steps += MixinConfigStep(project, buildSystem)
        }
        createPostSteps(steps)
        steps += FabricModJsonStep(project, buildSystem, config)
        return steps
    }

    override fun getMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val buildText = FabricTemplate.applyMultiModuleBuildGradle(project, buildSystem, config)
        val propText = FabricTemplate.applyMultiModuleGradleProp(project, buildSystem, config)
        val settingsText = FabricTemplate.applySettingsGradle(project, buildSystem, config)
        val files = GradleFiles(buildText, propText, settingsText)

        val steps = mutableListOf<CreatorStep>(
            SimpleGradleSetupStep(project, rootDirectory, buildSystem, files)
        )
        if (config.genSources) {
            steps += GenSourcesStep(project, rootDirectory)
        }
        config.license?.let {
            steps += LicenseStep(project, rootDirectory, it, config.authors.joinToString(", "))
        }
        if (config.mixins) {
            steps += MixinConfigStep(project, buildSystem)
        }
        return steps
    }

    override fun getPostMultiModuleSteps(projectBaseDir: Path): Iterable<CreatorStep> {
        val steps = mutableListOf<CreatorStep>()
        createPostSteps(steps)
        steps += FabricModJsonStep(project, buildSystem, config)
        return steps
    }

    private fun createPostSteps(steps: MutableList<CreatorStep>) {
        for (entry in config.entryPoints.groupBy { it.className }.entries.sortedBy { it.key }) {
            steps += CreateEntryPointStep(project, buildSystem, entry.key, entry.value)
        }
    }
}

class GenSourcesStep(
    private val project: Project,
    private val rootDirectory: Path
) : CreatorStep {

    override fun runStep(indicator: ProgressIndicator) {
        indicator.text = "Setting up project"
        indicator.text2 = "Running Gradle task: 'genSources'"
        runGradleTaskAndWait(project, rootDirectory) { settings ->
            settings.taskNames = listOf("genSources")
            settings.vmOptions = "-Xmx1G"
        }
        indicator.text2 = null
    }
}

class FabricModJsonStep(
    private val project: Project,
    private val buildSystem: BuildSystem,
    private val config: FabricProjectConfig
) : CreatorStep {

    override fun runStep(indicator: ProgressIndicator) {
        val text = FabricTemplate.applyFabricModJsonTemplate(project, buildSystem, config)
        val dir = buildSystem.dirsOrError.resourceDirectory

        indicator.text = "Indexing"

        project.runWriteTaskInSmartMode {
            indicator.text = "Creating 'fabric.mod.json'"

            val file = PsiFileFactory.getInstance(project).createFileFromText(JsonLanguage.INSTANCE, text)
            file.runWriteAction {
                val jsonFile = file as JsonFile
                val json = jsonFile.topLevelValue as? JsonObject ?: return@runWriteAction
                val generator = JsonElementGenerator(project)

                (json.findProperty("authors")?.value as? JsonArray)?.let { authorsArray ->
                    for (i in config.authors.indices) {
                        if (i != 0) {
                            authorsArray.addBefore(generator.createComma(), authorsArray.lastChild)
                        }
                        authorsArray.addBefore(generator.createStringLiteral(config.authors[i]), authorsArray.lastChild)
                    }
                }

                (json.findProperty("contact")?.value as? JsonObject)?.let { contactObject ->
                    val properties = mutableListOf<Pair<String, String>>()
                    val website = config.website
                    if (!website.isNullOrBlank()) {
                        properties += "website" to website
                    }
                    val repo = config.modRepo
                    if (!repo.isNullOrBlank()) {
                        properties += "repo" to repo
                    }
                    for (i in properties.indices) {
                        if (i != 0) {
                            contactObject.addBefore(generator.createComma(), contactObject.lastChild)
                        }
                        val key = StringUtil.escapeStringCharacters(properties[i].first)
                        val value = "\"" + StringUtil.escapeStringCharacters(properties[i].second) + "\""
                        contactObject.addBefore(generator.createProperty(key, value), contactObject.lastChild)
                    }
                }

                (json.findProperty("entrypoints")?.value as? JsonObject)?.let { entryPointsObject ->
                    val entryPointsByCategory = config.entryPoints
                        .groupBy { it.category }
                        .asSequence()
                        .sortedBy { it.key }
                        .toList()
                    for (i in entryPointsByCategory.indices) {
                        val entryPointCategory = entryPointsByCategory[i]
                        if (i != 0) {
                            entryPointsObject.addBefore(generator.createComma(), entryPointsObject.lastChild)
                        }
                        val values = generator.createValue<JsonArray>("[]")
                        for (j in entryPointCategory.value.indices) {
                            if (j != 0) {
                                values.addBefore(generator.createComma(), values.lastChild)
                            }
                            val entryPointReference = entryPointCategory.value[j].computeReference(project)
                            val value = generator.createStringLiteral(entryPointReference)
                            values.addBefore(value, values.lastChild)
                        }
                        val key = StringUtil.escapeStringCharacters(entryPointCategory.key)
                        val prop = generator.createProperty(key, "[]")
                        prop.value?.replace(values)
                        entryPointsObject.addBefore(prop, entryPointsObject.lastChild)
                    }
                }
            }
            CreatorStep.writeTextToFile(project, dir, FabricConstants.FABRIC_MOD_JSON, file.text)
        }
    }
}

class MixinConfigStep(
    private val project: Project,
    private val buildSystem: BuildSystem
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        val text = FabricTemplate.applyMixinConfigTemplate(project, buildSystem)
        val dir = buildSystem.dirsOrError.resourceDirectory
        runWriteTask {
            CreatorStep.writeTextToFile(project, dir, "${buildSystem.artifactId}.mixins.json", text)
        }
    }
}

class CreateEntryPointStep(
    private val project: Project,
    private val buildSystem: BuildSystem,
    private val qualifiedClassName: String,
    private val entryPoints: List<EntryPoint>
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        val dirs = buildSystem.dirsOrError

        indicator.text = "Indexing"

        val dotIndex = qualifiedClassName.lastIndexOf('.')
        val packageName = if (dotIndex == -1) {
            ""
        } else {
            qualifiedClassName.substring(0, dotIndex)
        }
        val className = qualifiedClassName.substring(dotIndex + 1)

        var directory = dirs.sourceDirectory
        for (part in packageName.split(".")) {
            directory = directory.resolve(part)
        }
        if (Files.notExists(directory)) {
            Files.createDirectories(directory)
        }

        val virtualDir = directory.virtualFile ?: return

        project.runWriteTaskInSmartMode {
            indicator.text = "Writing class: $className"

            val psiDir = PsiManager.getInstance(project).findDirectory(virtualDir) ?: return@runWriteTaskInSmartMode
            val clazz = try {
                JavaDirectoryService.getInstance().createClass(psiDir, className)
            } catch (e: IncorrectOperationException) {
                invokeLater {
                    val message = CodeInsightBundle.message(
                        "intention.error.cannot.create.class.message",
                        className
                    ) + '\n' + e.localizedMessage
                    Messages.showErrorDialog(
                        project,
                        message,
                        CodeInsightBundle.message("intention.error.cannot.create.class.title")
                    )
                }
                return@runWriteTaskInSmartMode
            }

            val editor = EditorHelper.openInEditor(clazz)

            clazz.containingFile.runWriteAction {
                val clientEntryPoints = entryPoints.filter { it.category == "client" }
                val serverEntryPoints = entryPoints.filter { it.category == "server" }
                val otherEntryPoints = entryPoints.filter { it.category != "client" && it.category != "server" }
                val entryPointsByInterface = entryPoints
                    .filter { it.type == EntryPoint.Type.CLASS }
                    .groupBy { it.interfaceName }
                    .entries
                    .sortedBy { it.key }
                val entryPointsByMethodNameAndSig = entryPoints
                    .filter { it.type == EntryPoint.Type.METHOD }
                    .groupBy { entryPoint ->
                        val functionalMethod = entryPoint.findFunctionalMethod(project) ?: return@groupBy null
                        val paramTypes = functionalMethod.parameterList.parameters.map { it.type.canonicalText }
                        (entryPoint.methodName ?: functionalMethod.name) to paramTypes
                    }
                    .entries
                    .mapNotNull { it.key?.let { k -> k to it.value } }
                    .sortedBy { it.first.first }

                val elementFactory = JavaPsiFacade.getElementFactory(project)

                var isClientClass = false
                var isServerClass = false
                if (clientEntryPoints.isNotEmpty()) {
                    if (serverEntryPoints.isEmpty() && otherEntryPoints.isEmpty()) {
                        addEnvironmentAnnotation(clazz, "CLIENT")
                        isClientClass = true
                    } else {
                        addSidedInterfaceEntryPoints(entryPointsByInterface, clazz, editor, "client")
                    }
                } else if (serverEntryPoints.isNotEmpty()) {
                    if (clientEntryPoints.isEmpty() && otherEntryPoints.isEmpty()) {
                        addEnvironmentAnnotation(clazz, "SERVER")
                        isServerClass = true
                    } else {
                        addSidedInterfaceEntryPoints(entryPointsByInterface, clazz, editor, "server")
                    }
                }

                for (eps in entryPointsByInterface) {
                    clazz.addImplements(eps.key)
                }
                implementAll(clazz, editor)

                for (eps in entryPointsByMethodNameAndSig) {
                    val functionalMethod = eps.second.first().findFunctionalMethod(project) ?: continue
                    val newMethod = clazz.addMethod(functionalMethod) ?: continue
                    val methodName = eps.first.first
                    newMethod.nameIdentifier?.replace(elementFactory.createIdentifier(methodName))
                    newMethod.modifierList.setModifierProperty(PsiModifier.PUBLIC, true)
                    newMethod.modifierList.setModifierProperty(PsiModifier.STATIC, true)
                    newMethod.modifierList.setModifierProperty(PsiModifier.ABSTRACT, false)
                    CreateFromUsageUtils.setupMethodBody(newMethod)
                    if (!isClientClass && eps.second.all { it.category == "client" }) {
                        addEnvironmentAnnotation(newMethod, "CLIENT")
                    } else if (!isServerClass && eps.second.all { it.category == "server" }) {
                        addEnvironmentAnnotation(newMethod, "SERVER")
                    }
                }
            }
        }
    }

    private fun addSidedInterfaceEntryPoints(
        entryPointsByInterface: List<Map.Entry<String, List<EntryPoint>>>,
        clazz: PsiClass,
        editor: Editor,
        side: String
    ) {
        val capsSide = side.toUpperCase(Locale.ROOT)
        var needsInterfaceFix = false
        for (eps in entryPointsByInterface) {
            if (eps.value.all { it.category == side }) {
                addEnvironmentInterfaceAnnotation(clazz, capsSide, eps.key)
                clazz.addImplements(eps.key)
                needsInterfaceFix = true
            }
        }
        if (needsInterfaceFix) {
            implementAll(clazz, editor)
            for (method in clazz.methods) {
                if (!method.hasAnnotation(FabricConstants.ENVIRONMENT_ANNOTATION)) {
                    addEnvironmentAnnotation(method, capsSide)
                }
            }
        }
    }

    private fun addEnvironmentAnnotation(owner: PsiModifierListOwner, envType: String) {
        owner.addAnnotation("@${FabricConstants.ENVIRONMENT_ANNOTATION}(${FabricConstants.ENV_TYPE}.$envType)")
    }

    private fun addEnvironmentInterfaceAnnotation(
        owner: PsiModifierListOwner,
        envType: String,
        interfaceQualifiedName: String
    ) {
        val annotationText = "@${FabricConstants.ENVIRONMENT_INTERFACE_ANNOTATION}(" +
            "value=${FabricConstants.ENV_TYPE}.$envType," +
            "itf=$interfaceQualifiedName.class" +
            ")"
        owner.addAnnotation(annotationText)
    }

    private fun implementAll(clazz: PsiClass, editor: Editor) {
        val methodsToImplement = OverrideImplementUtil.getMethodsToOverrideImplement(clazz, true)
            .map { PsiMethodMember(it) }
        OverrideImplementUtil.overrideOrImplementMethodsInRightPlace(
            editor,
            clazz,
            methodsToImplement,
            false,
            true
        )
    }
}
