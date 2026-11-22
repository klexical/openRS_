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

    @Test fun `the guard reads strings inside a template and decodes escapes`() {
        // The shapes the old code spelled units in (DriveSummarySheet's "${if (mpg) "MPG" else "L/100"}") and the
        // escaped degree sign (DashLayout writes \u00B0); each must fail.
        assertEquals(listOf(listOf("PSI")), violations("val a = \"\$v PSI\"").map { it.third })
        assertEquals(listOf(listOf("MPG"), listOf("L/100")),
            violations("val a = \"%.1f \${if (m) \"MPG\" else \"L/100\"}\"").map { it.third })
        assertEquals(listOf(listOf("°C")), violations("val a = \"\${t}\\u00B0C\"").map { it.third })
        assertEquals(listOf(listOf("°F")), violations("val a = \"\${\"%.0f\".format(t)}\\u00B0F\"").map { it.third })
        // A char literal '\u00B0' keeps the scanner in step, so the next string is still read.
        assertEquals(listOf(listOf("kPa")), violations("val c = '\\u00B0'; val a = \"\$v kPa\"").map { it.third })
        // A template still supplies no unit text, and a word inside one stays a word.
        assertEquals(0, violations("val a = \"\${label(\"bar\")} \$v\"").size)
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
         * one placeholder character in its string, so a template never supplies unit text to the string around it; a
         * string inside a `${…}` is read as a literal of its own (`"${if (m) "MPG" else "L/100"}"`). Escapes are decoded
         * before matching, so `"\u00B0C"` reads as °C.
         */
        fun literals(src: String): List<Pair<Int, String>> = LiteralScanner(src).run { code(0, inTemplate = false); out }

        private class LiteralScanner(private val src: String) {
            val out = mutableListOf<Pair<Int, String>>()
            private val n = src.length
            private fun lineAt(k: Int) = src.substring(0, k).count { it == '\n' } + 1

            /** Code from [start]; in a template, up to its closing } (returns the index after it), else to the end. */
            fun code(start: Int, inTemplate: Boolean): Int {
                var i = start
                var depth = 1
                while (i < n) {
                    when {
                        src.startsWith("//", i) -> { val j = src.indexOf('\n', i); i = if (j < 0) n else j }
                        src.startsWith("/*", i) -> { val j = src.indexOf("*/", i + 2); i = if (j < 0) n else j + 2 }
                        src[i] == '\'' -> i = charEnd(i)
                        src.startsWith("\"\"\"", i) -> i = string(i, raw = true)
                        src[i] == '"' -> i = string(i, raw = false)
                        inTemplate && src[i] == '{' -> { depth++; i++ }
                        inTemplate && src[i] == '}' -> { depth--; i++; if (depth == 0) return i }
                        else -> i++
                    }
                }
                return i
            }

            /** The index after the char literal opening at [i]: 'x', '\n' or '\uXXXX'. */
            private fun charEnd(i: Int): Int = when {
                i + 1 < n && src[i + 1] == '\\' -> if (i + 2 < n && src[i + 2] == 'u') i + 8 else i + 4
                else -> i + 3
            }

            /** Reads the string opening at [i], records it, and returns the index after its closing quote. */
            private fun string(i: Int, raw: Boolean): Int {
                val line = lineAt(i)
                val buf = StringBuilder()
                val close = if (raw) "\"\"\"" else "\""
                var j = i + close.length
                while (j < n && !src.startsWith(close, j)) {
                    when {
                        !raw && src[j] == '\\' && j + 1 < n -> {
                            if (src[j + 1] == 'u' && j + 5 < n) {
                                buf.append(src.substring(j + 2, j + 6).toIntOrNull(16)?.toChar() ?: '?'); j += 6
                            } else {
                                buf.append(when (val e = src[j + 1]) { 'n', 't', 'r', 'b' -> ' '; else -> e }); j += 2
                            }
                        }
                        src.startsWith("\${", j) -> { buf.append(TEMPLATE); j = code(j + 2, inTemplate = true) }
                        src[j] == '$' && j + 1 < n && (src[j + 1].isLetter() || src[j + 1] == '_') -> {
                            buf.append(TEMPLATE); j++
                            while (j < n && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                        }
                        else -> { buf.append(src[j]); j++ }
                    }
                }
                out += line to buf.toString()
                return minOf(n, j + close.length)
            }
        }
    }
}
