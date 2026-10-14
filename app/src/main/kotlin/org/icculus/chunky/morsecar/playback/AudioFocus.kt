package org.icculus.chunky.morsecar.playback

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Audio focus for the Morse player. Pauses on every kind of loss, including
 * "may duck": Morse is hard to copy at reduced volume under a navigation prompt.
 */
class AudioFocus(context: Context, private val listener: Listener) {
    interface Listener {
        /** [transient] losses (calls, navigation prompts) are followed by [onFocusRegained]. */
        fun onFocusLost(transient: Boolean)
        fun onFocusRegained()
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var hasFocus = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(MorseAudioEngine.AUDIO_ATTRIBUTES)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(::onChange, Handler(Looper.getMainLooper()))
        .build()

    /** Returns whether playback may start. */
    fun request(): Boolean {
        if (hasFocus) return true
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    fun abandon() {
        if (!hasFocus) return
        audioManager.abandonAudioFocusRequest(request)
        hasFocus = false
    }

    private fun onChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                listener.onFocusRegained()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                audioManager.abandonAudioFocusRequest(request)
                listener.onFocusLost(transient = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Not ours until regained, so a play pressed meanwhile asks again (and is refused during a call).
                hasFocus = false
                listener.onFocusLost(transient = true)
            }
        }
    }
}
