package dev.drewett.noisemachine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** State-only mixer checks: these do not start or emulate Android AudioTrack. */
class ProceduralMixerVoiceTest {
    @Test fun `stop rejects and closes later voices`() {
        val mixer = ProceduralMixer()
        mixer.stop()
        val voice = PcmStreamVoice("late", { java.io.ByteArrayInputStream(byteArrayOf(0, 64)) }, 1)
        mixer.setVoice(voice, 1f)
        try {
            assertFalse(mixer.isActive("late"))
            assertTrue(voice.awaitTermination(2000))
        } finally { mixer.remove("late"); voice.close() }
    }

    @Test fun `replacement removal and stop release stream producers`() {
        val mixer = ProceduralMixer()
        fun voice(id: String) = PcmStreamVoice(id, { java.io.ByteArrayInputStream(byteArrayOf(0, 64)) }, 1)
        val first = voice("same")
        val replacement = voice("same")
        val other = voice("other")
        try {
            mixer.setVoice(first, 1f)
            mixer.setVoice(replacement, 1f)
            assertTrue(first.awaitTermination(2000))
            assertEquals(setOf("same"), mixer.activeIds())
            mixer.remove("same")
            assertTrue(replacement.awaitTermination(2000))
            mixer.setVoice(other, 1f)
            mixer.stop()
            assertTrue(other.awaitTermination(2000))
            assertTrue(mixer.activeIds().isEmpty())
        } finally { first.close(); replacement.close(); other.close(); mixer.stop() }
    }

    @Test fun `fan participates in existing active voice removal path`() {
        // Arrange
        val mixer = ProceduralMixer()
        val fan = SampleLoopVoice("fan", floatArrayOf(0.25f))
        // Act
        mixer.setVoice(fan, 0.5f)
        // Assert
        assertTrue(mixer.isActive("fan"))
        assertEquals(setOf("fan"), mixer.activeIds())
        mixer.remove("fan")
        assertFalse(mixer.isActive("fan"))
        assertTrue(mixer.activeIds().isEmpty())
    }

    @Test fun `reactivating same id leaves one entry and preserves other voices`() {
        // Arrange
        val mixer = ProceduralMixer()
        mixer.setVoice(SampleLoopVoice("other", floatArrayOf(0.5f)), 1f)
        // Act
        mixer.setVoice(SampleLoopVoice("fan", floatArrayOf(0.25f)), 1f)
        mixer.setVoice(SampleLoopVoice("fan", floatArrayOf(-0.25f)), 0.5f)
        mixer.setVolume("fan", 0.75f)
        mixer.remove("fan")
        // Assert
        assertEquals(setOf("other"), mixer.activeIds())
    }
}
