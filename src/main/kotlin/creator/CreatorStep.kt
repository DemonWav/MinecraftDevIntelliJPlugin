/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.creator

import com.demonwav.mcdev.creator.buildsystem.BuildSystem
import com.demonwav.mcdev.creator.buildsystem.DirectorySet
import com.demonwav.mcdev.util.runWriteTask
import com.demonwav.mcdev.util.virtualFileOrError
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.ide.util.EditorHelper
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.createSmartPointer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/**
 * Represents a discrete kind of configuration code for a project. Project creators use these to spread implementation
 * out between platforms without coupling them, and allow overriding for more complex options for specific build systems
 * without coupling the build system and the platform.
 */
interface CreatorStep {

    fun runStep(indicator: ProgressIndicator)

    companion object {
        private val scheduledReformats: MutableList<SmartPsiElementPointer<PsiFile>> = mutableListOf()
        fun runAllReformats() {
            runWriteTask {
                for (scheduledReformat in scheduledReformats) {
                    val file = scheduledReformat.element ?: continue
                    PsiDocumentManager.getInstance(file.project).getDocument(file)?.setReadOnly(false)
                    ReformatCodeProcessor(file, false).run()
                }
            }
            scheduledReformats.clear()
        }

        fun writeTextToFile(
            project: Project,
            targetDir: Path,
            fileName: String,
            text: String
        ): VirtualFile {
            if (Files.notExists(targetDir)) {
                Files.createDirectories(targetDir)
            }
            val file = targetDir.resolve(fileName)
            Files.write(file, text.toByteArray(Charsets.UTF_8), WRITE, CREATE, TRUNCATE_EXISTING)
            val vFile = file.virtualFileOrError

            // Reformat the code to match their code style
            runReadAction {
                if (project.isDisposed) {
                    return@runReadAction
                }
                PsiManager.getInstance(project).findFile(vFile)?.let {
                    scheduledReformats += it.createSmartPointer()
                }
            }

            return vFile
        }

        fun writeText(file: Path, text: String, psiManager: PsiManager? = null) {
            Files.write(file, text.toByteArray(Charsets.UTF_8), CREATE, TRUNCATE_EXISTING, WRITE)
            psiManager?.findFile(file.virtualFileOrError)?.let {
                PsiDocumentManager.getInstance(psiManager.project).getDocument(it)?.setReadOnly(false)
                ReformatCodeProcessor(it, false).run()
            }
        }
    }
}

class BasicJavaClassStep(
    private val project: Project,
    private val buildSystem: BuildSystem,
    private val className: String,
    private val classText: String,
    private val openInEditor: Boolean = true,
    private val rootProvider: (BuildSystem) -> Path = { it.dirsOrError.sourceDirectory }
) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        runWriteTask {
            indicator.text = "Writing class: $className"
            val files = className.split(".")
            val className = files.last()

            val sourceDir = getMainClassDirectory(rootProvider(buildSystem), files)

            val classFile = CreatorStep.writeTextToFile(project, sourceDir, "$className.java", classText)

            if (openInEditor) {
                // Set the editor focus on the created class
                PsiManager.getInstance(project).findFile(classFile)?.let { classPsi ->
                    EditorHelper.openInEditor(classPsi)
                }
            }
        }
    }

    private fun getMainClassDirectory(dir: Path, files: List<String>): Path {
        val directories = files.slice(0 until files.lastIndex).toTypedArray()
        val outputDir = Paths.get(dir.toAbsolutePath().toString(), *directories)
        Files.createDirectories(outputDir)
        return outputDir
    }
}

class CreateDirectoriesStep(private val buildSystem: BuildSystem, private val directory: Path) : CreatorStep {
    override fun runStep(indicator: ProgressIndicator) {
        buildSystem.directories = DirectorySet.create(directory)
    }
}
