package com.mytracksapp.logging

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * T01 (RF-02, RF-11) evidence test — asserts no external logging/crash-reporting dependency has
 * been introduced anywhere in the build configuration. [FileLogger] is the only logging mechanism
 * in this app.
 */
class NoExternalLoggingDependencyTest {

    private val forbiddenPatterns = listOf(
        Regex("(?i)timber"),
        Regex("(?i)crashlytics"),
        Regex("(?i)sentry"),
        Regex("(?i)firebase[-.]crashlytics"),
    )

    private fun findRepoFile(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error(
            "Could not locate $relativePath under any ancestor directory " +
                "(searched upward from ${File(".").absoluteFile})",
        )
    }

    private fun assertNoForbiddenMatch(content: String, sourceLabel: String) {
        forbiddenPatterns.forEach { pattern ->
            assertFalse(
                "$sourceLabel unexpectedly references a forbidden logging/crash-reporting " +
                    "dependency matching `${pattern.pattern}`",
                pattern.containsMatchIn(content),
            )
        }
    }

    @Test
    fun `gradle version catalog references no external logging or crash-reporting library`() {
        val content = findRepoFile("gradle/libs.versions.toml").readText()
        assertNoForbiddenMatch(content, "gradle/libs.versions.toml")
    }

    @Test
    fun `app build script references no external logging or crash-reporting dependency or plugin`() {
        val content = findRepoFile("app/build.gradle.kts").readText()
        assertNoForbiddenMatch(content, "app/build.gradle.kts")
    }
}
