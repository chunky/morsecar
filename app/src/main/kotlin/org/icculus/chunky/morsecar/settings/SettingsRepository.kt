package org.icculus.chunky.morsecar.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.icculus.chunky.morsecar.morse.MorseTiming
import java.io.IOException

/** The slider values, shared by the phone UI (which writes them) and the playback service. */
class SettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<PlaybackSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toSettings() }
        .distinctUntilChanged()

    suspend fun update(settings: PlaybackSettings) {
        val s = settings.clamped()
        store.edit {
            it[CHARACTER_WPM] = s.timing.characterWpm
            it[LETTER_SPACING_WPM] = s.timing.letterSpacingWpm
            it[WORD_SPACING_WPM] = s.timing.wordSpacingWpm
            it[TONE_HZ] = s.toneHz
        }
    }

    /** Switches to the next speed preset; used by the Android Auto button. */
    suspend fun cyclePreset() {
        store.edit {
            val next = it.toSettings().withNextPreset()
            it[CHARACTER_WPM] = next.timing.characterWpm
            it[LETTER_SPACING_WPM] = next.timing.letterSpacingWpm
            it[WORD_SPACING_WPM] = next.timing.wordSpacingWpm
        }
    }

    private fun Preferences.toSettings(): PlaybackSettings {
        val d = PlaybackSettings.DEFAULT
        return PlaybackSettings(
            MorseTiming(
                this[CHARACTER_WPM] ?: d.timing.characterWpm,
                this[LETTER_SPACING_WPM] ?: d.timing.letterSpacingWpm,
                this[WORD_SPACING_WPM] ?: d.timing.wordSpacingWpm,
            ),
            this[TONE_HZ] ?: d.toneHz,
        ).clamped()
    }

    private companion object {
        val CHARACTER_WPM = doublePreferencesKey("character_wpm")
        val LETTER_SPACING_WPM = doublePreferencesKey("letter_spacing_wpm")
        val WORD_SPACING_WPM = doublePreferencesKey("word_spacing_wpm")
        val TONE_HZ = doublePreferencesKey("tone_hz")
    }
}
