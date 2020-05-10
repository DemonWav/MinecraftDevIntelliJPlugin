
@file:Suppress("Duplicates")
package com.demonwav.mcdev.platform.placeholderapi

import com.demonwav.mcdev.buildsystem.BuildSystem
import com.demonwav.mcdev.platform.PlatformType
import com.demonwav.mcdev.platform.ProjectConfiguration
import com.demonwav.mcdev.util.runWriteTask
import com.intellij.ide.util.EditorHelper
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager

class PlaceholderApiProjectConfiguration : ProjectConfiguration() {

    var mcVersion = ""

    override var type: PlatformType = PlatformType.PLACEHOLDERAPI

    override fun create(project: Project, buildSystem: BuildSystem, indicator: ProgressIndicator) {
        if (project.isDisposed) {
            return
        }

        val baseConfig = base ?: return
        val dirs = buildSystem.directories ?: return

        runWriteTask {
            indicator.text = "Writing main class"

            var file = dirs.sourceDirectory
            val files = baseConfig.mainClass.split(".").toTypedArray()
            val className = files.last()
            val packageName = baseConfig.mainClass.substring(0, baseConfig.mainClass.length - className.length - 1)
            file = getMainClassDirectory(files, file)

            val mainClassFile = file.findOrCreateChildData(this, className + ".java")
            PlaceholderApiTemplate.applyMainClassTemplate(
                project,
                mainClassFile,
                packageName,
                className
            )

            PsiManager.getInstance(project).findFile(mainClassFile)?.let { mainClassPsi ->
                EditorHelper.openInEditor(mainClassPsi)
            }
        }
    }

    override fun setupDependencies(buildSystem: BuildSystem) {}
}