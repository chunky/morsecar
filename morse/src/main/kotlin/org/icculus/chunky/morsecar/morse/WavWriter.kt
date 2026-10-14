package org.icculus.chunky.morsecar.morse

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes 16-bit mono PCM as a WAV file. */
object WavWriter {
    fun write(out: OutputStream, samples: ShortArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16)
            .putShort(1)                // PCM
            .putShort(1)                // mono
            .putInt(sampleRate)
            .putInt(sampleRate * 2)     // byte rate
            .putShort(2)                // block align
            .putShort(16)               // bits per sample
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (sample in samples) buffer.putShort(sample)
        out.write(buffer.array())
    }
}
