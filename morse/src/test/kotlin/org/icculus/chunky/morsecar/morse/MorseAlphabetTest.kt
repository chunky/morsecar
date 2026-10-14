package org.icculus.chunky.morsecar.morse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MorseAlphabetTest {
    private val alphabet = MorseAlphabet.default

    @Test
    fun bundledAlphabetHasLettersDigitsAndPunctuation() {
        for (c in ('A'..'Z') + ('0'..'9') + ".,?'/()=+-\"@".toList()) {
            assertTrue("missing '$c'", alphabet.isEncodable(c))
        }
        assertEquals(".-", alphabet.patternFor('A'))
        assertEquals("--..--", alphabet.patternFor(','))
        assertEquals("-----", alphabet.patternFor('0'))
    }

    @Test
    fun bundledPatternsAreUnique() {
        val patterns = alphabet.symbols.map { alphabet.patternFor(it) }
        assertEquals(patterns.size, patterns.toSet().size)
    }

    @Test
    fun lowercaseAndSpaceAreNotSymbols() {
        assertFalse(alphabet.isEncodable('a'))
        assertFalse(alphabet.isEncodable(' '))
        assertNull(alphabet.patternFor('*'))
    }

    @Test
    fun characterUnitsCountMarksAndInnerGaps() {
        assertEquals(1, alphabet.characterUnits('E'))   // .
        assertEquals(3, alphabet.characterUnits('T'))   // -
        assertEquals(5, alphabet.characterUnits('A'))   // . -
        assertEquals(19, alphabet.characterUnits('0'))  // - - - - -
    }

    @Test
    fun parsesJson() {
        val custom = MorseAlphabet.fromJson("""{"symbols": {"E": ".", "T": "-"}}""")
        assertEquals(setOf('E', 'T'), custom.symbols)
    }

    @Test
    fun rejectsBadJson() {
        val bad = listOf(
            """{}""",
            """{"symbols": {"EE": "."}}""",
            """{"symbols": {"e": "."}}""",
            """{"symbols": {" ": "."}}""",
            """{"symbols": {"E": ""}}""",
            """{"symbols": {"E": ".x"}}""",
            """{"symbols": {"E": 1}}""",
        )
        for (json in bad) {
            assertThrows(json, IllegalArgumentException::class.java) { MorseAlphabet.fromJson(json) }
        }
    }
}
