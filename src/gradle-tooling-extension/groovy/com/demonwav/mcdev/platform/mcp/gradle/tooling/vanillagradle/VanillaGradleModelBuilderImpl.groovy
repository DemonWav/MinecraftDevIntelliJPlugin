/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.mcp.gradle.tooling.vanillagradle

import org.gradle.api.Project
import org.jetbrains.annotations.NotNull
import org.jetbrains.plugins.gradle.tooling.ErrorMessageBuilder
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService

class VanillaGradleModelBuilderImpl implements ModelBuilderService {

    @Override
    boolean canBuild(String modelName) {
        return VanillaGradleModel.name == modelName
    }

    @Override
    Object buildAll(String modelName, Project project) {
        return new VanillaGradleModelImpl(project.plugins.hasPlugin('org.spongepowered.gradle.vanilla'))
    }

    @Override
    ErrorMessageBuilder getErrorMessageBuilder(@NotNull Project project, @NotNull Exception e) {
        return ErrorMessageBuilder.create(
                project, e, "MinecraftDev import errors"
        ).withDescription("Unable to build MinecraftDev VanillaGradle project configuration")
    }
}
