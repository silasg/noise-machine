package dev.drewett.noisemachine.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SampleLoopVoiceTest {
    @Test fun `one wrap preserves exact sample sequence`() {
        // Arrange
        val voice = SampleLoopVoice("fan", floatArrayOf(-1f, 0f, 1f))
        val output = FloatArray(5)
        // Act
        voice.render(output, output.size, 44100)
        // Assert
        assertEquals("fan", voice.id)
        assertArrayEquals(floatArrayOf(-1f, 0f, 1f, -1f, 0f), output, 0f)
    }

    @Test fun `many wraps continue across successive caller buffers`() {
        // Arrange
        val samples = floatArrayOf(-0.75f, 0.25f, 0.5f)
        val voice = SampleLoopVoice("fan", samples)
        var cursor = 0
        // Act
        for (size in listOf(1, 2, 17, 1024, 5, 1024)) {
            val output = FloatArray(size)
            voice.render(output, size, 44100)
            // Assert
            assertArrayEquals(FloatArray(size) { samples[(cursor + it) % samples.size] }, output, 0f)
            cursor += size
        }
    }

    @Test fun `partial render leaves tail untouched and advances only requested frames`() {
        // Arrange
        val voice = SampleLoopVoice("fan", floatArrayOf(0.25f, 0.5f, 0.75f))
        val output = FloatArray(5) { 99f }
        // Act
        voice.render(output, 2, 44100)
        // Assert
        assertArrayEquals(floatArrayOf(0.25f, 0.5f, 99f, 99f, 99f), output, 0f)
        voice.render(output, 1, 44100)
        assertArrayEquals(floatArrayOf(0.75f, 0.5f, 99f, 99f, 99f), output, 0f)
    }

    @Test fun `zero frames and empty output do not advance cursor`() {
        // Arrange
        val voice = SampleLoopVoice("fan", floatArrayOf(0.25f, 0.5f))
        val output = floatArrayOf(99f)
        // Act
        voice.render(output, 0, 44100)
        voice.render(FloatArray(0), 0, 44100)
        // Assert
        assertArrayEquals(floatArrayOf(99f), output, 0f)
        voice.render(output, 1, 44100)
        assertArrayEquals(floatArrayOf(0.25f), output, 0f)
    }

    @Test fun `singleton loop overwrites reused output through many wraps`() {
        // Arrange
        val voice = SampleLoopVoice("fan", floatArrayOf(-0.5f))
        val output = FloatArray(1024) { 99f }
        // Act
        repeat(4) { voice.render(output, output.size, 44100) }
        // Assert
        assertArrayEquals(FloatArray(1024) { -0.5f }, output, 0f)
    }

    @Test fun `invalid renders reject before modifying output or cursor`() {
        // Arrange
        val voice = SampleLoopVoice("fan", floatArrayOf(0.25f, 0.5f))
        val output = floatArrayOf(99f)
        // Act
        assertThrows(IllegalArgumentException::class.java) { voice.render(output, -1, 44100) }
        assertThrows(IllegalArgumentException::class.java) { voice.render(output, 2, 44100) }
        assertThrows(IllegalArgumentException::class.java) { voice.render(output, 1, 48000) }
        assertThrows(IllegalArgumentException::class.java) { voice.render(output, 1, 0) }
        // Assert
        assertArrayEquals(floatArrayOf(99f), output, 0f)
        voice.render(output, 1, 44100)
        assertArrayEquals(floatArrayOf(0.25f), output, 0f)
    }

    @Test fun `empty invalid samples and nonpositive source rates are rejected`() {
        // Arrange
        val invalidSamples = listOf(floatArrayOf(), floatArrayOf(Float.NaN),
            floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf(Float.NEGATIVE_INFINITY),
            floatArrayOf(1.01f), floatArrayOf(-1.01f))
        // Act / Assert
        invalidSamples.forEach { samples ->
            assertThrows(IllegalArgumentException::class.java) { SampleLoopVoice("fan", samples) }
        }
        for (rate in listOf(0, -44100)) {
            assertThrows(IllegalArgumentException::class.java) {
                SampleLoopVoice("fan", floatArrayOf(0f), rate)
            }
        }
    }

    @Test fun `custom matching source rate is supported`() {
        // Arrange
        val voice = SampleLoopVoice("fan", floatArrayOf(0.5f), 48000)
        val output = FloatArray(2)
        // Act
        voice.render(output, 2, 48000)
        // Assert
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), output, 0f)
    }
}
