package com.openrs.dash.can

import android.content.Context
import com.openrs.dash.data.ForscanCatalog
import com.openrs.dash.data.ForscanPid

/**
 * Data-driven PID registry backed by [ForscanCatalog].
 *
 * Provides:
 *  - SLCAN query generation from catalog metadata
 *  - Formula-based decode of Mode 22 responses for PIDs that have decode info
 *  - Lookup by ECU response ID + DID
 *
 * Currently used as a fallback in [ObdResponseParser] — existing hardcoded
 * parsers take precedence for complex multi-field decoders.
 */
object PidRegistry {

    private data class DecodablePid(
        val pid: ForscanPid,
        val requestId: Int,
        val responseId: Int
    )

    /** (responseId, did) → DecodablePid, built on first load. */
    private var index: Map<Pair<Int, Int>, DecodablePid> = emptyMap()
    @Volatile private var loaded = false

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            ForscanCatalog.load(ctx)
            buildIndex()
            loaded = true
        }
    }

    private fun buildIndex() {
        val catalog = ForscanCatalog.catalog ?: return
        val map = mutableMapOf<Pair<Int, Int>, DecodablePid>()
        for (mod in catalog.modules) {
            val reqId = mod.canRequestId.removePrefix("0x").toIntOrNull(16) ?: continue
            val respId = mod.canResponseId.removePrefix("0x").toIntOrNull(16) ?: continue
            for (pid in mod.pids) {
                if (pid.did.isEmpty() || pid.formula.isEmpty()) continue
                val did = pid.did.removePrefix("0x").toIntOrNull(16) ?: continue
                map[Pair(respId, did)] = DecodablePid(pid, reqId, respId)
            }
        }
        index = map
    }

    /**
     * Build the SLCAN Mode 22 query frame for [requestId] and [did].
     * Format: `t{requestId:03X}80322{did:04X}00000000\r`
     */
    fun buildSlcanQuery(requestId: Int, did: Int): String =
        "t%03X80322%04X00000000\r".format(requestId, did)

    /**
     * Attempt to decode a Mode 22 positive response using catalog formulas.
     *
     * @param ecuResponseId the CAN ID the response arrived on (e.g. 0x7E8)
     * @param did the 16-bit DID from the response
     * @param b4 first data byte (unsigned)
     * @param b5 second data byte (unsigned), 0 if not present
     * @return (fieldName, decodedValue) or null if no formula is registered
     */
    fun decode(ecuResponseId: Int, did: Int, b4: Int, b5: Int = 0): Pair<String, Double>? {
        val entry = index[Pair(ecuResponseId, did)] ?: return null
        val pid = entry.pid
        if (pid.field.isEmpty() || pid.formula.isEmpty()) return null
        val value = evaluateFormula(pid.formula, b4, b5) ?: return null
        if (value.isInfinite() || value.isNaN()) return null
        return Pair(pid.field, value)
    }

    /**
     * Simple formula evaluator supporting:
     *  - `A` (first data byte, unsigned), `B` (second data byte, unsigned)
     *  - `signed(expr)` for 16-bit two's complement interpretation
     *  - Standard arithmetic: `+`, `-`, `*`, `/`, parentheses
     *  - Numeric literals (integer and decimal)
     */
    internal fun evaluateFormula(formula: String, a: Int, b: Int): Double? {
        return try {
            val tokens = tokenize(formula, a.toDouble(), b.toDouble())
            parseExpression(tokens, intArrayOf(0))
        } catch (_: Exception) {
            null
        }
    }

    // ── Tokenizer ────────────────────────────────────────────────────────────

    private sealed class Token {
        data class Num(val value: Double) : Token()
        data class Op(val op: Char) : Token()
        object LParen : Token()
        object RParen : Token()
        object Signed : Token()
    }

    private fun tokenize(formula: String, a: Double, b: Double): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < formula.length) {
            val c = formula[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { tokens.add(Token.LParen); i++ }
                c == ')' -> { tokens.add(Token.RParen); i++ }
                c == '+' || c == '*' || c == '/' -> { tokens.add(Token.Op(c)); i++ }
                c == '-' -> {
                    val prev = tokens.lastOrNull()
                    if (prev == null || prev is Token.Op || prev is Token.LParen) {
                        // Unary minus: parse the number immediately
                        i++
                        val start = i
                        while (i < formula.length && (formula[i].isDigit() || formula[i] == '.')) i++
                        if (i > start) {
                            tokens.add(Token.Num(-formula.substring(start, i).toDouble()))
                        } else {
                            // Negative of next token: push -1 * ...
                            tokens.add(Token.Num(-1.0))
                            tokens.add(Token.Op('*'))
                        }
                    } else {
                        tokens.add(Token.Op('-')); i++
                    }
                }
                c == 'A' -> { tokens.add(Token.Num(a)); i++ }
                c == 'B' -> { tokens.add(Token.Num(b)); i++ }
                c == 'C' -> { tokens.add(Token.Num(0.0)); i++ }
                c == 's' && formula.startsWith("signed", i) -> {
                    tokens.add(Token.Signed); i += 6
                }
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < formula.length && (formula[i].isDigit() || formula[i] == '.')) i++
                    tokens.add(Token.Num(formula.substring(start, i).toDouble()))
                }
                else -> i++
            }
        }
        return tokens
    }

    // ── Recursive descent parser ─────────────────────────────────────────────

    private fun parseExpression(tokens: List<Token>, pos: IntArray): Double {
        var left = parseTerm(tokens, pos)
        while (pos[0] < tokens.size) {
            val tok = tokens[pos[0]]
            if (tok is Token.Op && (tok.op == '+' || tok.op == '-')) {
                pos[0]++
                val right = parseTerm(tokens, pos)
                left = if (tok.op == '+') left + right else left - right
            } else break
        }
        return left
    }

    private fun parseTerm(tokens: List<Token>, pos: IntArray): Double {
        var left = parsePrimary(tokens, pos)
        while (pos[0] < tokens.size) {
            val tok = tokens[pos[0]]
            if (tok is Token.Op && (tok.op == '*' || tok.op == '/')) {
                pos[0]++
                val right = parsePrimary(tokens, pos)
                left = if (tok.op == '*') left * right else left / right
            } else break
        }
        return left
    }

    private fun parsePrimary(tokens: List<Token>, pos: IntArray): Double {
        if (pos[0] >= tokens.size) return 0.0
        return when (val tok = tokens[pos[0]]) {
            is Token.Num -> { pos[0]++; tok.value }
            is Token.LParen -> {
                pos[0]++
                val v = parseExpression(tokens, pos)
                if (pos[0] < tokens.size && tokens[pos[0]] is Token.RParen) pos[0]++
                v
            }
            is Token.Signed -> {
                pos[0]++
                if (pos[0] < tokens.size && tokens[pos[0]] is Token.LParen) pos[0]++
                val v = parseExpression(tokens, pos)
                if (pos[0] < tokens.size && tokens[pos[0]] is Token.RParen) pos[0]++
                val raw = v.toInt()
                val signed = if (raw > 32767) raw - 65536 else raw
                signed.toDouble()
            }
            else -> { pos[0]++; 0.0 }
        }
    }
}
