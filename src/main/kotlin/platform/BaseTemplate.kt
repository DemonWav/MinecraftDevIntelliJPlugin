/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform

import com.demonwav.mcdev.util.License
import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.project.Project
import java.time.ZonedDateTime

abstract class BaseTemplate {

    protected fun Project.applyTemplate(
        templateName: String,
        properties: Map<String, *>? = null
    ): String {
        val manager = FileTemplateManager.getInstance(this)
        val template = manager.getJ2eeTemplate(templateName)

        val allProperties = manager.defaultProperties.toMutableMap()
        properties?.let { prop -> allProperties.putAll(prop) }

        return template.getText(allProperties)
    }

    fun applyLicenseTemplate(
        project: Project,
        license: License,
        author: String
    ): String {
        val props = mapOf(
            "YEAR" to ZonedDateTime.now().year.toString(),
            "AUTHOR" to author
        )
        return project.applyTemplate("${license.id}.txt", props)
    }
}
