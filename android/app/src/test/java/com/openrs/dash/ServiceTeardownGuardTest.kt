package com.openrs.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * No CanDataService may outlive its test (PR #281's CI: an undestroyed service's collector threw after Robolectric's reset
 * and failed MapRecordingPollTest with UncaughtExceptionsBeforeTest; N2's gate on 16c5c2c, the same class). The failure is
 * timing-dependent, so this pins the rule on the test sources: a service is built only through [TestServices], and every
 * test class that uses it destroys them in an `@After` with `TestServices.destroyAll()`.
 */
class ServiceTeardownGuardTest {
    private val dir = File("src/test/java/com/openrs/dash")          // the unit-test working directory is the module (app/)
    private val sources by lazy {
        assertTrue("test sources found at ${dir.absolutePath}", dir.isDirectory)
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.relativeTo(dir).path to it.readText() }
    }
    private val self = "ServiceTeardownGuardTest.kt"
    private val helper = "TestServices.kt"

    @Test fun `a CanDataService is built only through TestServices`() {
        val direct = sources.filter { (name, text) ->
            name != helper && name != self && Regex("""buildService\(\s*CanDataService""").containsMatchIn(text)
        }.keys.sorted()
        assertEquals("build it with TestServices.create() / controller() so it is destroyed at teardown", emptyList<String>(), direct)
    }

    @Test fun `every test class that creates a service destroys it at teardown`() {
        val users = sources.filter { (name, text) -> name != helper && name != self && Regex("""TestServices\.(create|controller)\(""").containsMatchIn(text) }
        val missing = users.filter { (_, text) ->
            !Regex("""@After[^\n]*\n?[^\n]*TestServices\.destroyAll\(\)|@After\s+fun\s+\w+\(\)\s*\{[^}]*TestServices\.destroyAll\(\)""").containsMatchIn(text)
        }.keys.sorted()
        assertTrue("at least one service test is checked", users.isNotEmpty())
        assertEquals("call TestServices.destroyAll() in an @After, before cancelling recorders", emptyList<String>(), missing)
    }
}
