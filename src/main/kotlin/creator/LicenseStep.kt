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

import com.demonwav.mcdev.platform.fabric.creator.FabricTemplate
import com.demonwav.mcdev.util.License
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class LicenseStep(
    private val project: Project,
    private val rootDirectory: Path,
    private val license: License,
    private val author: String
) : CreatorStep {

    override fun runStep(indicator: ProgressIndicator) {
        val licenseFile = rootDirectory.resolve("LICENSE")

        val fileText = FabricTemplate.applyLicenseTemplate(project, license, author)

        Files.write(
            licenseFile,
            fileText.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING
        )
    }
}
