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

import com.demonwav.mcdev.platform.mcp.gradle.tooling.vanillagradle.VanillaGradleModel
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData
import org.gradle.tooling.model.idea.IdeaModule
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension

class VanillaGradleProjectResolverExtension : AbstractProjectResolverExtension() {

    override fun getExtraProjectModelClasses(): Set<Class<out Any>> =
        setOf(VanillaGradleModel::class.java)

    override fun getToolingExtensionsClasses() = extraProjectModelClasses

    override fun populateModuleExtraModels(gradleModule: IdeaModule, ideModule: DataNode<ModuleData>) {
        val vgData = resolverCtx.getExtraProject(gradleModule, VanillaGradleModel::class.java)
        if (vgData != null) {
            val gradleProjectPath = gradleModule.gradleProject.projectIdentifier.projectPath
            val suffix = if (gradleProjectPath.endsWith(':')) "" else ":"
            val decompileTaskName = gradleProjectPath + suffix + "decompile"
            ideModule.createChild(VanillaGradleData.KEY, VanillaGradleData(ideModule.data, decompileTaskName))
        }

        super.populateModuleExtraModels(gradleModule, ideModule)
    }
}
