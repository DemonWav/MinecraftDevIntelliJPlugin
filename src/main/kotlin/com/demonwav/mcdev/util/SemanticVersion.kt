/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2020 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.util

import com.demonwav.mcdev.util.SemanticVersion.Companion.VersionPart.ReleasePart
import com.demonwav.mcdev.util.SemanticVersion.Companion.VersionPart.TextPart

/**
 * Represents a comparable and generalised "semantic version".
 * Each constituent part (delimited by periods in a version string) contributes
 * to the version ranking with decreasing priority from left to right.
 */
class SemanticVersion(
    private val parts: List<VersionPart>,
    private val buildMetadata: String = ""
) : Comparable<SemanticVersion> {

    private fun createVersionString(): String {
        val mainPart = parts.joinToString(".") { it.versionString }
        return if (buildMetadata.isBlank()) {
            mainPart
        } else {
            "$mainPart+$buildMetadata"
        }
    }

    private val versionString = createVersionString()

    override fun compareTo(other: SemanticVersion): Int =
        naturalOrder<VersionPart>().lexicographical().compare(parts, other.parts)

    override fun equals(other: Any?) =
        when (other) {
            is SemanticVersion -> parts.size == other.parts.size && parts.zip(other.parts).all { (a, b) -> a == b }
            else -> false
        }

    override fun hashCode() = parts.hashCode()

    override fun toString() = versionString

    fun take(count: Int): SemanticVersion {
        return SemanticVersion(parts.take(count))
    }

    fun startsWith(other: SemanticVersion): Boolean {
        if (other.parts.size > this.parts.size) {
            return false
        }
        for (i in other.parts.indices) {
            if (this.parts[i] != other.parts[i]) {
                return false
            }
        }
        return true
    }

    companion object {
        /**
         * The higher the priority value associated with a certain part, the higher it is ranked in comparisons.
         * Unknown parts will always be "rated" lower and equal among each other.
         */
        val TEXT_PRIORITIES = mapOf(
            "snapshot" to 0,
            "rc" to 1,
            "pre" to 1
        )

        /**
         * All separators allowed between a number and a modifier (i.e. (numbered) text part).
         */
        private val SEPARATORS = listOf('-', '_')

        /**
         * Creates a simple release version where each provided value forms a part (read from left to right).
         */
        fun release(vararg parts: Int) = SemanticVersion(parts.map { ReleasePart(it, it.toString()) })

        /**
         * Parses a version string into a comparable representation.
         * @throws IllegalArgumentException if any part of the version string cannot be parsed as integer or split into text parts.
         */
        fun parse(value: String): SemanticVersion {
            fun parseInt(part: String): Int =
                if (part.all { it.isDigit() }) {
                    part.toInt()
                } else {
                    throw IllegalArgumentException(
                        "Failed to parse version part as integer: $part " +
                            "(whole version text: $value)"
                    )
                }

            // We need to support pre-releases/RCs and snapshots as well
            fun parseTextPart(subParts: List<String>, separator: Char): VersionPart =
                if (subParts.size == 2) {
                    val version = parseInt(subParts[0])
                    val (text, number) = subParts[1].span { !it.isDigit() }
                    // Pure text parts always are considered older than numbered ones
                    // Since we don't handle negative version numbers, -1 is guaranteed to be smaller than any numbered part
                    val versionNumber = if (number.isEmpty()) -1 else parseInt(number)
                    TextPart(version, separator, text, versionNumber)
                } else {
                    throw IllegalArgumentException(
                        "Failed to split text version part into two: ${subParts.first()}$separator"
                    )
                }

            val mainPartAndMetadata = value.split("+", limit = 2)
            val mainPart = mainPartAndMetadata[0]
            val metadata = mainPartAndMetadata.getOrNull(1) ?: ""

            val parts = mainPart.split('.').map { part ->
                val separator = SEPARATORS.find { it in part }
                if (separator != null) {
                    parseTextPart(part.split(separator, limit = 2), separator)
                } else {
                    // Forge has a single version which should be 14.8.* but is actually 14.v8.*
                    val numberPart = if (part.startsWith('v')) {
                        part.substring(1)
                    } else {
                        part
                    }
                    ReleasePart(parseInt(numberPart), part)
                }
            }
            return SemanticVersion(parts, metadata)
        }

        sealed class VersionPart : Comparable<VersionPart> {
            abstract val versionString: String

            data class ReleasePart(val version: Int, override val versionString: String) : VersionPart() {
                override fun compareTo(other: VersionPart) =
                    when (other) {
                        is ReleasePart -> version - other.version
                        is TextPart -> if (version != other.version) version - other.version else 1
                    }
            }

            data class TextPart(val version: Int, val separator: Char, val text: String, val number: Int) :
                VersionPart() {
                private val priority = TEXT_PRIORITIES[text.toLowerCase()] ?: -1

                override val versionString = "$version$separator$text${if (number == -1) "" else number.toString()}"

                override fun compareTo(other: VersionPart) =
                    when (other) {
                        is ReleasePart -> if (version != other.version) version - other.version else -1
                        is TextPart ->
                            when {
                                version != other.version -> version - other.version
                                text != other.text -> priority - other.priority
                                else -> number - other.number
                            }
                    }

                override fun hashCode(): Int {
                    return version + 31 * text.hashCode() + 31 * number
                }

                override fun equals(other: Any?): Boolean {
                    return when (other) {
                        is TextPart -> other.version == version && other.text == text && other.number == number
                        else -> false
                    }
                }
            }
        }
    }
}
