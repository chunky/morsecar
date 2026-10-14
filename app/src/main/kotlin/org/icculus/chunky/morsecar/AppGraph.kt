package org.icculus.chunky.morsecar

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.icculus.chunky.morsecar.library.Library
import org.icculus.chunky.morsecar.library.PositionStore
import org.icculus.chunky.morsecar.playback.NowPlaying
import org.icculus.chunky.morsecar.settings.SettingsRepository

private val Context.settingsStore by preferencesDataStore("settings")
private val Context.libraryStore by preferencesDataStore("library")
private val Context.positionsStore by preferencesDataStore("positions")

/** Process-wide singletons shared by the UI and the playback service. */
class AppGraph(context: Context) {
    /** For work that must finish even if the screen or service that started it goes away, like saving. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context.settingsStore)
    val library = Library(context, context.libraryStore)
    val positions = PositionStore(context.positionsStore)
    val nowPlaying = NowPlaying()
}

val Context.appGraph: AppGraph get() = (applicationContext as MorseCarApplication).graph
