package org.icculus.chunky.morsecar.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import org.icculus.chunky.morsecar.settings.PlaybackSettings

/** Custom session commands, and the buttons that send them (notification, lock screen, Android Auto). */
@OptIn(UnstableApi::class)
object PlaybackCommands {
    val SKIP_BACK_15 = SessionCommand("org.icculus.chunky.morsecar.SKIP_BACK_15", Bundle.EMPTY)
    val SKIP_FORWARD_15 = SessionCommand("org.icculus.chunky.morsecar.SKIP_FORWARD_15", Bundle.EMPTY)
    val SKIP_BACK_30 = SessionCommand("org.icculus.chunky.morsecar.SKIP_BACK_30", Bundle.EMPTY)
    val SKIP_FORWARD_30 = SessionCommand("org.icculus.chunky.morsecar.SKIP_FORWARD_30", Bundle.EMPTY)
    val NEXT_PRESET = SessionCommand("org.icculus.chunky.morsecar.NEXT_PRESET", Bundle.EMPTY)

    val all = listOf(SKIP_BACK_15, SKIP_FORWARD_15, SKIP_BACK_30, SKIP_FORWARD_30, NEXT_PRESET)

    /** How far each skip command moves, in milliseconds of playback. */
    fun skipMs(command: SessionCommand): Long? = when (command.customAction) {
        SKIP_BACK_15.customAction -> -MorsePlayer.SKIP_SHORT_MS
        SKIP_FORWARD_15.customAction -> MorsePlayer.SKIP_SHORT_MS
        SKIP_BACK_30.customAction -> -MorsePlayer.SKIP_LONG_MS
        SKIP_FORWARD_30.customAction -> MorsePlayer.SKIP_LONG_MS
        else -> null
    }

    /** ±15 s beside play/pause, ±30 s next to them, and the speed preset in the overflow. */
    fun buttons(settings: PlaybackSettings): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
            .setDisplayName("Back 15 seconds")
            .setSessionCommand(SKIP_BACK_15)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_15)
            .setDisplayName("Forward 15 seconds")
            .setSessionCommand(SKIP_FORWARD_15)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
            .setDisplayName("Back 30 seconds")
            .setSessionCommand(SKIP_BACK_30)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Forward 30 seconds")
            .setSessionCommand(SKIP_FORWARD_30)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_PLAYBACK_SPEED)
            .setDisplayName("Speed: ${settings.preset?.name ?: "custom"} (tap for next preset)")
            .setSessionCommand(NEXT_PRESET)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )
}
