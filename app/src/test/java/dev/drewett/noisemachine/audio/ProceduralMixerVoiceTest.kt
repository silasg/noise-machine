package dev.drewett.noisemachine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** State-only mixer checks: these do not start or emulate Android AudioTrack. */
class ProceduralMixerVoiceTest {
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
