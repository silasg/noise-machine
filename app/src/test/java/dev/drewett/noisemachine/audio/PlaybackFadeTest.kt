package dev.drewett.noisemachine.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFadeTest {
    @Test fun fadesAndCompletesOnlyAtEnd() {
        // Arrange
        val fade = PlaybackFade()
        var completed = false
        // Act
        fade.start(0f, 0L) { completed = true }
        val halfway = fade.tick(500L)
        // Assert
        assertEquals(0.5f, halfway, 0.001f)
        assertEquals(false, completed)
        assertEquals(0f, fade.tick(1000L), 0.001f)
        assertEquals(true, completed)
    }

    @Test fun reversalContinuesFromCurrentGainAndCancelsOldCompletion() {
        // Arrange
        val fade = PlaybackFade()
        var stopped = false
        fade.start(0f, 0L) { stopped = true }
        fade.tick(500L)
        // Act
        fade.start(1f, 500L)
        // Assert
        assertEquals(0.5f, fade.tick(500L), 0.001f)
        assertEquals(0.75f, fade.tick(1000L), 0.001f)
        assertEquals(1f, fade.tick(1500L), 0.001f)
        assertEquals(false, stopped)
    }
}
