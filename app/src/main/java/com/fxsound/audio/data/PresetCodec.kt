package com.fxsound.audio.data

import com.fxsound.audio.model.FxSoundPreset

/**
 * JSON codec for user custom presets.
 *
 * Deliberately uses no library dependency: the preset schema is fixed and tiny, and a
 * hand-written codec is unit-testable on the JVM without Android's org.json stubs. Pure
 * static methods with no Android framework calls, so the DataStore layer can feed it the
 * raw stored string.
 */
object PresetCodec {

    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_CLARITY = "clarity"
    private const val KEY_AMBIENCE = "ambience"
    private const val KEY_SURROUND = "surround"
    private const val KEY_DYNAMIC_BOOST = "dynamic_boost"
    private const val KEY_BASS = "bass"
    private const val KEY_EQ_GAINS = "eq_gains"

    fun encode(presets: List<FxSoundPreset>): String {
        val sb = StringBuilder()
        sb.append('[')
        for ((index, preset) in presets.withIndex()) {
            if (index > 0) sb.append(',')
            sb.append('{')
            appendString(sb, KEY_ID, preset.id); sb.append(',')
            appendString(sb, KEY_NAME, preset.name); sb.append(',')
            appendString(sb, KEY_DESCRIPTION, preset.description); sb.append(',')
            appendNumber(sb, KEY_CLARITY, preset.clarity); sb.append(',')
            appendNumber(sb, KEY_AMBIENCE, preset.ambience); sb.append(',')
            appendNumber(sb, KEY_SURROUND, preset.surround); sb.append(',')
            appendNumber(sb, KEY_DYNAMIC_BOOST, preset.dynamicBoost); sb.append(',')
            appendNumber(sb, KEY_BASS, preset.bass); sb.append(',')
            sb.append('"').append(KEY_EQ_GAINS).append('"').append(":[")
            for ((i, gain) in preset.eqGains.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append(gain.toString())
            }
            sb.append(']')
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /**
     * Decodes a stored JSON payload. Any malformed entry is skipped, not fatal: a
     * corrupted preset must never prevent the remaining presets from loading.
     * Returns an empty list for null, blank or unparsable input.
     */
    fun decode(json: String?): List<FxSoundPreset> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            parsePresetArray(MinimalJsonParser(json))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parsePresetArray(parser: MinimalJsonParser): List<FxSoundPreset> {
        val presets = mutableListOf<FxSoundPreset>()
        parser.skipWhitespace()
        if (!parser.consume('[')) return emptyList()
        parser.skipWhitespace()
        if (parser.consume(']')) return presets
        while (true) {
            parser.skipWhitespace()
            val parsed: FxSoundPreset? = parsePresetObject(parser)
            if (parsed != null) presets.add(parsed)
            parser.skipWhitespace()
            if (parser.consume(',')) continue
            if (parser.consume(']')) return presets
            throw MinimalJsonParser.JsonSyntax()
        }
    }

    private fun parsePresetObject(parser: MinimalJsonParser): FxSoundPreset? {
        if (!parser.consume('{')) return null
        var id: String? = null
        var name: String? = null
        var description = ""
        var clarity = 5.0
        var ambience = 2.0
        var surround = 3.0
        var dynamicBoost = 4.0
        var bass = 4.0
        var eqGains: List<Float>? = null
        var sawEqGains = false

        parser.skipWhitespace()
        if (parser.consume('}')) {
            return null // an object with no fields cannot be identified
        }
        while (true) {
            parser.skipWhitespace()
            val key = parser.parseString()
            parser.skipWhitespace()
            if (!parser.consume(':')) throw MinimalJsonParser.JsonSyntax()
            parser.skipWhitespace()
            when (key) {
                KEY_ID -> id = parser.parseString()
                KEY_NAME -> name = parser.parseString()
                KEY_DESCRIPTION -> description = parser.parseString()
                KEY_EQ_GAINS -> {
                    eqGains = parseEqArray(parser)
                    sawEqGains = true
                }
                KEY_CLARITY -> clarity = parser.parseNumber()
                KEY_AMBIENCE -> ambience = parser.parseNumber()
                KEY_SURROUND -> surround = parser.parseNumber()
                KEY_DYNAMIC_BOOST -> dynamicBoost = parser.parseNumber()
                KEY_BASS -> bass = parser.parseNumber()
                else -> parser.skipValue()
            }
            parser.skipWhitespace()
            if (parser.consume(',')) continue
            if (parser.consume('}')) break
            throw MinimalJsonParser.JsonSyntax()
        }
        return FxSoundPreset(
            id = id ?: return null,
            name = name ?: return null,
            description = description,
            clarity = clampEffect(clarity),
            ambience = clampEffect(ambience),
            surround = clampEffect(surround),
            dynamicBoost = clampEffect(dynamicBoost),
            bass = clampEffect(bass),
            eqGains = if (sawEqGains) eqGains!! else throw MinimalJsonParser.JsonSyntax()
        )
    }

    private fun parseEqArray(parser: MinimalJsonParser): List<Float> {
        parser.skipWhitespace()
        if (!parser.consume('[')) throw MinimalJsonParser.JsonSyntax()
        parser.skipWhitespace()
        // An empty array is rejected: the schema is exactly 10 bands.
        if (parser.consume(']')) throw MinimalJsonParser.JsonSyntax()
        val gains = mutableListOf<Float>()
        while (true) {
            parser.skipWhitespace()
            gains.add(clampEq(parser.parseNumber()))
            parser.skipWhitespace()
            if (parser.consume(',')) continue
            if (parser.consume(']')) break
            throw MinimalJsonParser.JsonSyntax()
        }
        if (gains.size != 10) throw MinimalJsonParser.JsonSyntax()
        return gains
    }

    private fun clampEffect(value: Double): Float {
        return value.toFloat().coerceIn(0.0f, 10.0f)
    }

    private fun clampEq(value: Double): Float {
        return value.toFloat().coerceIn(-14.0f, 14.0f)
    }

    private fun appendNumber(sb: StringBuilder, key: String, value: Float) {
        sb.append('"').append(key).append('"').append(':').append(value.toString())
    }

    private fun appendString(sb: StringBuilder, key: String, value: String) {
        sb.append('"').append(key).append('"').append(':').append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}

/**
 * A tiny hand-recursive descent parser for exactly the preset schema written by encode():
 * strings, numbers, arrays of numbers and objects. The schema is fixed so the parser can
 * be this small; anything unexpected throws [JsonSyntax], which the decode() entry point
 * converts into "skip the malformed preset".
 */
class MinimalJsonParser(private val json: String) {

    class JsonSyntax : Exception()

    private var pos = 0

    fun skipWhitespace() {
        while (pos < json.length && json[pos].isWhitespace()) pos++
    }

    fun peekIs(ch: Char): Boolean {
        return pos < json.length && json[pos] == ch
    }

    fun consume(ch: Char): Boolean {
        if (peekIs(ch)) {
            pos++
            return true
        }
        return false
    }

    fun parseString(): String {
        if (!consume('"')) throw JsonSyntax()
        val sb = StringBuilder()
        while (true) {
            if (pos >= json.length) throw JsonSyntax()
            when (val ch = json[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= json.length) throw JsonSyntax()
                    when (val escape = json[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 > json.length) throw JsonSyntax()
                            sb.append(json.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> throw JsonSyntax()
                    }
                }
                else -> sb.append(ch)
            }
        }
    }

    /**
     * Parses a JSON number and returns it as a Double, throwing on malformed syntax.
     * Audits non-numeric values by failing rather than silently returning 0.
     */
    fun parseNumber(): Double {
        val start = pos
        if (pos < json.length && (json[pos] == '-' || json[pos] == '+')) pos++
        var sawDigit = false
        while (pos < json.length) {
            when (json[pos]) {
                '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> { sawDigit = true; pos++ }
                '.', 'e', 'E', '-', '+' -> pos++
                else -> break
            }
        }
        if (!sawDigit) throw JsonSyntax()
        return json.substring(start, pos).toDoubleOrNull() ?: throw JsonSyntax()
    }

    /**
     * Consumes an unexpected value (used by the object parser to skip unknown fields so
     * a future schema version round-trips through older code without failing).
     */
    fun skipValue() {
        skipWhitespace()
        when {
            pos >= json.length -> throw JsonSyntax()
            peekIs('"') -> parseString()
            peekIs('[') -> {
                pos++
                skipWhitespace()
                if (!consume(']')) {
                    while (true) {
                        skipValue()
                        skipWhitespace()
                        if (consume(',')) continue
                        if (consume(']')) break
                        throw JsonSyntax()
                    }
                }
            }
            peekIs('{') -> { parseObjectShapeOnly() }
            else -> parseNumber()
        }
    }

    private fun parseObjectShapeOnly() {
        pos++ // consume '{'
        skipWhitespace()
        if (consume('}')) return
        while (true) {
            skipWhitespace()
            parseString()
            skipWhitespace()
            if (!consume(':')) throw JsonSyntax()
            skipValue()
            skipWhitespace()
            if (consume(',')) continue
            if (consume('}')) return
            throw JsonSyntax()
        }
    }
}
