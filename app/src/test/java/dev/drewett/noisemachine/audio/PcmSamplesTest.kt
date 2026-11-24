package dev.drewett.noisemachine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmSamplesTest {
    @Test fun `signed little endian decoding preserves extrema zero and byte order`() {
        // Arrange
        val bytes = byteArrayOf(0, -128, -1, -1, 0, 0, 1, 0, 0, 1, -1, 127)
        // Act
        val samples = PcmSamples.decodeSigned16LittleEndian(bytes)
        // Assert
        assertArrayEquals(floatArrayOf(-1f, -1f / 32768, 0f, 1f / 32768,
            256f / 32768, 32767f / 32768), samples, 0f)
    }

    @Test fun `empty or truncated pcm is rejected`() {
        // Arrange
        val invalid = listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(1, 2, 3))
        // Act / Assert
        invalid.forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) {
                PcmSamples.decodeSigned16LittleEndian(bytes)
            }
        }
    }

    @Test fun `decoded pcm loops without inserted silence or boundary duplication`() {
        // Arrange
        val samples = PcmSamples.decodeSigned16LittleEndian(byteArrayOf(0, 32, 0, 64, 0, -96))
        val voice = SampleLoopVoice("fan", samples)
        val output = FloatArray(8)
        // Act
        voice.render(output, output.size, 44100)
        // Assert
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, -0.75f, 0.25f, 0.5f, -0.75f, 0.25f, 0.5f), output, 0f)
    }
}
