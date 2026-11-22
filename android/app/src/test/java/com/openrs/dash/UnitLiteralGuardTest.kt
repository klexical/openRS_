package com.openrs.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #464: every unit a screen prints comes from the one formatter (`ui/units/Units.kt`). This guard reads the source of
 * every `ui/` file, `service/HudOverlayService.kt` and `data/DashLayout.kt` and fails on a string literal that spells
 * a unit, so a new hard-coded "PSI" or "°C" can't come back beside the formatter.
 *
 * - Long spellings ([LONG]) fail as a whole word anywhere in a literal.
 * - Short spellings that are also ordinary words or styles ([SHORT]: bar, ft, m, L, km, mi) fail only after a number,
 *   a format spec or a template and one space ("%.1f bar", "$v ft"), so the Custom Dashboard "bar" cell style,
 *   "SHORT FT" and "5m ago" stay.
 * - Comments are not read. Allowed: `ui/units/` (the formatter), What's New (release notes), `AppSettings.kt` (stored ids
 *   and preference keys, not display), the vector path data in `BrandArt.kt`, and [ALLOWED] — the PERF 0-60 / 0-100 MPH
 *   timer's names (a name, not a unit setting, #469).
 */
class UnitLiteralGuardTest {

    @Test fun `no screen file spells a unit outside the formatter`() {
        val root = File("src/main/java/com/openrs/dash")
        assertTrue("source root not found from ${File(".").absolutePath}", root.isDirectory)
        val files = File(root, "ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/ui/units/") }
            .filterNot { it.name in SKIPPED_FILES }
            .toList() + listOf(File(root, "service/HudOverlayService.kt"), File(root, "data/DashLayout.kt"))
        val hits = files.flatMap { f -> violations(f.readText()).map { (line, lit, words) -> "${f.name}:$line $words \"$lit\"" } }
        assertEquals("unit spellings outside ui/units/Units.kt:\n" + hits.joinToString("\n"), emptyList<String>(), hits)
    }

    @Test fun `the guard fails on a planted PSI literal in a screen file`() {
        val planted = """
            // a comment that says PSI is fine
            @Composable fun Cell(v: Double) { Text("${'$'}{"%.1f".format(v)} PSI") }
        """.trimIndent()
        assertEquals(listOf(listOf("PSI")), violations(planted).map { it.third })
    }

    @Test fun `short spellings fail only after a number and stay as words or styles`() {
        assertEquals(1, violations("""val a = "%.1f bar"""").size)
        assertEquals(1, violations("val a = \"\$v ft\"").size)
        assertEquals(0, violations("""val a = "bar"; val b = "SHORT FT"; val c = "BAR"""").size)
        assertEquals(0, violations("val a = \"TPMS \${age}m ago\"").size)
    }

    companion object {
        val LONG = listOf("psi", "PSI", "kPa", "KPA", "km/h", "KM/H", "kph", "KPH", "mph", "MPH", "°C", "°F", "L/100",
            "MPG", "Nm", "lb·ft", "lb-ft", "hPa", "inHg", "L/hr", "L/h", "MPa", "gal")
        val SHORT = listOf("bar", "BAR", "ft", "m", "L", "km", "mi")
        val SKIPPED_FILES = setOf("WhatsNewDialog.kt", "AppSettings.kt", "BrandArt.kt")

        /** Literals allowed by content: the PERF timer's names (#469). */
        val ALLOWED = setOf("0-60 MPH", "0-100 MPH", "0-60 MPH · 0-100 MPH TIMER", "STOP AT 60 MPH",
            "TARGETS IN MPH · 0-97 / 0-161 KM/H")

        private const val TEMPLATE = "\u0000"
        private val NUMERIC = """(?:%[-+ 0#,]*\d*(?:\.\d+)?[dfs]|$TEMPLATE|\d)"""
        private val longRes = LONG.map { Regex("""(?<![A-Za-z])${Regex.escape(it)}(?![A-Za-z])""") to it }
        private val shortRes = SHORT.map { Regex("""$NUMERIC ${Regex.escape(it)}(?![A-Za-z0-9])""") to it }

        /** (line, literal text, unit words) for each literal of [src] that spells a unit. */
        fun violations(src: String): List<Triple<Int, String, List<String>>> = literals(src).mapNotNull { (line, lit) ->
            if (lit in ALLOWED) return@mapNotNull null
            val words = longRes.filter { it.first.containsMatchIn(lit) }.map { it.second } +
                shortRes.filter { it.first.containsMatchIn(lit) }.map { it.second }
            if (words.isEmpty()) null else Triple(line, lit.replace(TEMPLATE, "\${…}"), words)
        }

        /**
         * The string literals of Kotlin [src] with their line, comments skipped. A template (`${…}` or `$name`) reads as
         * one placeholder character, so a template never supplies unit text and a string inside one is not read.
         */
        fun literals(src: String): List<Pair<Int, String>> {
            val out = mutableListOf<Pair<Int, String>>()
            var i = 0
            val n = src.length
            fun lineAt(k: Int) = src.substring(0, k).count { it == '\n' } + 1
            fun skipTemplate(start: Int): Int {          // start = index after "${"; returns index after the closing }
                var k = start
                var depth = 1
                while (k < n && depth > 0) {
                    when (src[k]) {
                        '{' -> depth++
                        '}' -> depth--
                        '"' -> { k++; while (k < n && src[k] != '"') { if (src[k] == '\\') k++; k++ } }
                    }
                    k++
                }
                return k
            }
            while (i < n) {
                when {
                    src.startsWith("//", i) -> { val j = src.indexOf('\n', i); i = if (j < 0) n else j }
                    src.startsWith("/*", i) -> { val j = src.indexOf("*/", i + 2); i = if (j < 0) n else j + 2 }
                    src[i] == '\'' -> { i += if (i + 1 < n && src[i + 1] == '\\') 4 else 3 }
                    src.startsWith("\"\"\"", i) -> {
                        val j = src.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it }
                        out += lineAt(i) to raw(src.substring(i + 3, j))
                        i = j + 3
                    }
                    src[i] == '"' -> {
                        val line = lineAt(i)
                        val buf = StringBuilder()
                        var j = i + 1
                        while (j < n && src[j] != '"') {
                            when {
                                src[j] == '\\' -> { buf.append(src, j, minOf(j + 2, n)); j += 2 }
                                src.startsWith("\${", j) -> { buf.append(TEMPLATE); j = skipTemplate(j + 2) }
                                src[j] == '$' && j + 1 < n && (src[j + 1].isLetter() || src[j + 1] == '_') -> {
                                    buf.append(TEMPLATE); j++
                                    while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                                }
                                else -> { buf.append(src[j]); j++ }
                            }
                        }
                        out += line to buf.toString()
                        i = j + 1
                    }
                    else -> i++
                }
            }
            return out
        }

        private fun raw(s: String) = s.replace(Regex("""\$\{[^}]*}|\$[A-Za-z_]\w*"""), TEMPLATE)
    }
}
