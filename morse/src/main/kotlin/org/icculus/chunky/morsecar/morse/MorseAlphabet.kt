package org.icculus.chunky.morsecar.morse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Which characters can be sent, and as what. Loaded from JSON of the form
 * `{"symbols": {"A": ".-", ...}}`; the bundled one is `alphabet.json`.
 */
class MorseAlphabet(patterns: Map<Char, String>) {
    private val patterns: Map<Char, String> = patterns.toMap()

    private val characterUnits: Map<Char, Int> = patterns.mapValues { (_, pattern) ->
        pattern.sumOf { if (it == '.') MorseTiming.DIT_UNITS else MorseTiming.DAH_UNITS } +
            (pattern.length - 1) * MorseTiming.INTRA_CHARACTER_GAP_UNITS
    }

    init {
        for ((c, pattern) in patterns) {
            require(!c.isWhitespace() && !c.isLowerCase()) { "Symbol must be non-whitespace and not lowercase: '$c'" }
            require(pattern.isNotEmpty() && pattern.all { it == '.' || it == '-' }) {
                "Pattern for '$c' must be dits and dahs: \"$pattern\""
            }
        }
    }

    val symbols: Set<Char> get() = patterns.keys

    /** Dits and dahs as a string of '.' and '-', or null if [c] can't be sent. */
    fun patternFor(c: Char): String? = patterns[c]

    fun isEncodable(c: Char): Boolean = c in patterns

    /** Length of [c]'s marks and the gaps between them, in character units. */
    fun characterUnits(c: Char): Int =
        characterUnits[c] ?: throw IllegalArgumentException("Not encodable: '$c'")

    companion object {
        private const val DEFAULT_RESOURCE = "alphabet.json"

        val default: MorseAlphabet by lazy {
            val stream = MorseAlphabet::class.java.getResourceAsStream(DEFAULT_RESOURCE)
                ?: error("Missing resource $DEFAULT_RESOURCE")
            fromJson(stream.bufferedReader().use { it.readText() })
        }

        fun fromJson(json: String): MorseAlphabet {
            val root = Json.parseToJsonElement(json).jsonObject
            val symbols = root["symbols"] as? JsonObject
                ?: throw IllegalArgumentException("Alphabet JSON needs a \"symbols\" object")
            val patterns = symbols.entries.associate { (key, value) ->
                require(key.length == 1) { "Symbol keys must be single characters: \"$key\"" }
                require(value is JsonPrimitive && value.isString) { "Pattern for \"$key\" must be a string" }
                key[0] to value.content
            }
            return MorseAlphabet(patterns)
        }
    }
}
