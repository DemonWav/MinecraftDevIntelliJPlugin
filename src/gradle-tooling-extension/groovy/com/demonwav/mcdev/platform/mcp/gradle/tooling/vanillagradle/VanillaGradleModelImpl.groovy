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

class VanillaGradleModelImpl implements VanillaGradleModel, Serializable {

    private final boolean hasVanillaGradle

    VanillaGradleModelImpl(boolean hasVanillaGradle) {
        this.hasVanillaGradle = hasVanillaGradle
    }

    @Override
    boolean hasVanillaGradle() {
        return hasVanillaGradle
    }
}
