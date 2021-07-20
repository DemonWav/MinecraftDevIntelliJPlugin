/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.mcp.vanillagradle

import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.AbstractExternalEntityData
import com.intellij.openapi.externalSystem.model.project.ModuleData

data class VanillaGradleData(
    val module: ModuleData,
    val decompileTaskName: String
) : AbstractExternalEntityData(module.owner) {
    companion object {
        val KEY = Key.create(VanillaGradleData::class.java, ProjectKeys.TASK.processingWeight + 1)
    }
}
