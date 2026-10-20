package org.icculus.chunky.morsecar.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.R as Media3R
import androidx.media3.session.SessionCommand
import org.icculus.chunky.morsecar.morse.MorsePresets
import org.icculus.chunky.morsecar.morse.MorseTiming
import org.icculus.chunky.morsecar.playback.PlaybackCommands
import org.icculus.chunky.morsecar.settings.PlaybackSettings
import org.icculus.chunky.morsecar.settings.formatWpm
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(vm: MainViewModel, onOpenLibrary: () -> Unit) {
    val player by vm.player.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val book by vm.nowPlayingBook.collectAsStateWithLifecycle()
    val rawPosition by vm.rawPosition.collectAsStateWithLifecycle()
    var showSpeedControls by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                player.title ?: "MorseCar",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenLibrary) { Text("Books") }
        }
        player.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val current = book
            when {
                current != null -> BookTextView(current, rawPosition, Modifier.fillMaxSize())
                player.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                !player.hasBook -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Choose a book to start listening.")
                    Button(onClick = onOpenLibrary, modifier = Modifier.padding(top = 8.dp)) { Text("Choose a book") }
                }
            }
        }
        if (player.hasBook) {
            PositionBar(player, vm::seekTo)
            TransportRow(player, onPlayPause = vm::playPause, onCommand = vm::sendCommand)
        }
        TextButton(onClick = { showSpeedControls = !showSpeedControls }) {
            Text(if (showSpeedControls) "Hide speed and tone" else "Speed and tone: ${settings.describe()}")
        }
        if (showSpeedControls) SpeedControls(settings, vm)
    }
}

@Composable
private fun PositionBar(player: PlayerUiState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs.coerceAtLeast(1)
    val fraction = dragging ?: (player.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Slider(
        value = fraction,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onSeek((it * duration).toLong()) }
            dragging = null
        },
        enabled = player.durationMs > 0,
    )
    Row {
        Text(formatDuration((fraction * duration).toLong()), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(formatDuration(player.durationMs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TransportRow(player: PlayerUiState, onPlayPause: () -> Unit, onCommand: (SessionCommand) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(Media3R.drawable.media3_icon_skip_back_30, "Back 30 seconds") { onCommand(PlaybackCommands.SKIP_BACK_30) }
        TransportButton(Media3R.drawable.media3_icon_skip_back_15, "Back 15 seconds") { onCommand(PlaybackCommands.SKIP_BACK_15) }
        FilledIconButton(onClick = onPlayPause, modifier = Modifier.size(64.dp)) {
            Icon(
                painterResource(if (player.playWhenReady) Media3R.drawable.media3_icon_pause else Media3R.drawable.media3_icon_play),
                contentDescription = if (player.playWhenReady) "Pause" else "Play",
                modifier = Modifier.size(36.dp),
            )
        }
        TransportButton(Media3R.drawable.media3_icon_skip_forward_15, "Forward 15 seconds") { onCommand(PlaybackCommands.SKIP_FORWARD_15) }
        TransportButton(Media3R.drawable.media3_icon_skip_forward_30, "Forward 30 seconds") { onCommand(PlaybackCommands.SKIP_FORWARD_30) }
    }
}

@Composable
private fun TransportButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun SpeedControls(settings: PlaybackSettings, vm: MainViewModel) {
    val timing = settings.timing
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in MorsePresets.all) {
                FilterChip(
                    selected = settings.preset == preset,
                    onClick = { vm.applyPreset(preset) },
                    label = { Text(preset.name) },
                )
            }
        }
        SettingSlider("Letters", timing.characterWpm, PlaybackSettings.CHARACTER_WPM_RANGE, WPM_STEP, "${formatWpm(timing.characterWpm)} WPM") {
            vm.setTiming(MorseTiming(it, timing.letterSpacingWpm, timing.wordSpacingWpm))
        }
        SettingSlider("Letter gaps", timing.letterSpacingWpm, PlaybackSettings.SPACING_WPM_RANGE, WPM_STEP, "${formatWpm(timing.letterSpacingWpm)} WPM") {
            vm.setTiming(MorseTiming(timing.characterWpm, it, timing.wordSpacingWpm))
        }
        SettingSlider("Word gaps", timing.wordSpacingWpm, PlaybackSettings.SPACING_WPM_RANGE, WPM_STEP, "${formatWpm(timing.wordSpacingWpm)} WPM") {
            vm.setTiming(MorseTiming(timing.characterWpm, timing.letterSpacingWpm, it))
        }
        SettingSlider("Tone", settings.toneHz, PlaybackSettings.TONE_HZ_RANGE, TONE_STEP_HZ, "${settings.toneHz.roundToInt()} Hz") {
            vm.setToneHz(it)
        }
    }
}

@Composable
private fun SettingSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    /** Dragging snaps to multiples of this, so values stay readable. */
    step: Double,
    valueText: String,
    onChange: (Double) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(88.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(range)) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(valueText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp).padding(start = 8.dp))
    }
}

private const val WPM_STEP = 0.5
private const val TONE_STEP_HZ = 10.0
