package dev.drewett.noisemachine.audio

import java.io.ByteArrayInputStream
import org.junit.Test
import org.junit.Assert.*

class PcmStreamVoiceTest {
    @Test fun closeReturnsWhileReadAndCloseAreBlocked() {
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val source = object : java.io.InputStream() {
            var first = true
            override fun read(): Int = -1
            @Synchronized override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (first) {
                    first = false
                    bytes[offset] = 0; bytes[offset + 1] = 64
                    return 2
                }
                reading.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                return -1
            }
            @Synchronized override fun close() { }
        }
        val voice = PcmStreamVoice("blocked", { source }, 1)
        voice.render(FloatArray(1), 1, 44100)
        val closer = Thread { try { voice.close() } finally { done.countDown() } }
        try {
            assertTrue(reading.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val silence = FloatArray(4) { 1f }
            voice.render(silence, silence.size, 44100)
            assertArrayEquals(FloatArray(4), silence, 0f)
            closer.start()
            assertTrue("close must not wait on producer read monitor", done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            closer.join(2000)
            voice.close()
            assertTrue("producer exits after read is released", voice.awaitTermination(2000))
        }
    }

    @Test fun underrunResumesAtNextSample() {
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val source = object : java.io.InputStream() {
            var reads = 0
            override fun read(): Int = -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) {
                    reading.countDown()
                    release.await()
                }
                bytes[offset] = 0
                bytes[offset + 1] = if (reads == 1) 64 else -64
                return 2
            }
        }
        val voice = PcmStreamVoice("recover", { source }, 1)
        try {
            val out = FloatArray(1)
            voice.render(out, 1, 44100)
            assertEquals(.5f, out[0], 0f)
            assertTrue(reading.await(2, java.util.concurrent.TimeUnit.SECONDS))
            voice.render(out, 1, 44100)
            assertEquals(0f, out[0], 0f)
            release.countDown()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            do {
                voice.render(out, 1, 44100)
                if (out[0] != 0f) break
                Thread.yield()
            } while (System.nanoTime() < deadline)
            assertEquals(-.5f, out[0], 0f)
        } finally {
            release.countDown()
            voice.close()
            assertTrue(voice.awaitTermination(2000))
        }
    }

    @Test fun runtimeReadFailureTerminatesAndDrainsToSilence() {
        val error = java.io.IOException("runtime read")
        val source = object : java.io.InputStream() {
            var first = true
            override fun read(): Int = -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (!first) throw error
                first = false
                bytes[offset] = 0; bytes[offset + 1] = 64
                return 2
            }
        }
        val voice = PcmStreamVoice("runtime", { source }, 1)
        try {
            val out = FloatArray(1)
            voice.render(out, 1, 44100)
            assertEquals(.5f, out[0], 0f)
            assertTrue(voice.awaitTermination(2000))
            assertSame(error, voice.failure)
            voice.render(out, 1, 44100)
            assertEquals(0f, out[0], 0f)
        } finally { voice.close() }
    }

    @Test fun repeatedRefillsPreserveSequenceAcrossRingAndSourceWraps() {
        val voice = PcmStreamVoice("wrap", { ByteArrayInputStream(byteArrayOf(0, 64, 0, -64)) }, 3)
        try {
            val out = FloatArray(1)
            repeat(60) { index ->
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                do {
                    voice.render(out, 1, 44100)
                    if (out[0] != 0f) break
                    Thread.yield()
                } while (System.nanoTime() < deadline)
                assertEquals(if (index % 2 == 0) .5f else -.5f, out[0], 0f)
            }
        } finally { voice.close(); assertTrue(voice.awaitTermination(2000)) }
    }

    @Test fun fullRingBoundsSourceAdvanceIndependentOfLength() {
        val advanced = java.util.concurrent.atomic.AtomicInteger()
        val closed = java.util.concurrent.CountDownLatch(1)
        val source = object : java.io.InputStream() {
            override fun read(): Int = error("buffered bulk reads expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                repeat(length) { bytes[offset + it] = if (it % 2 == 0) 0 else 64 }
                advanced.addAndGet(length)
                return length // Simulates unbounded source; no full-file allocation.
            }
            override fun close() { closed.countDown() }
        }
        val voice = PcmStreamVoice("bounded", { source }, 2)
        try {
            // Constructor readiness means ring is full. Producer must not fetch
            // more than its one fixed 8192-byte read-ahead buffer without demand.
            assertEquals(8192, advanced.get())
            val out = FloatArray(2)
            voice.render(out, 2, 44100)
            assertArrayEquals(floatArrayOf(.5f, .5f), out, 0f)
        } finally {
            voice.close()
            assertTrue(voice.awaitTermination(2000))
            assertTrue(closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun oneByteReadsPreserveSignedSamplePairing() {
        val voice = PcmStreamVoice("partial", {
            object : ByteArrayInputStream(byteArrayOf(0, 64, 0, -64)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                    super.read(bytes, offset, minOf(length, 1))
            }
        }, 4)
        try {
            val out = FloatArray(4)
            voice.render(out, 4, 44100)
            assertArrayEquals(floatArrayOf(.5f, -.5f, .5f, -.5f), out, 0f)
        } finally { voice.close() }
    }

    @Test fun interruptedConstructionCancelsWorkerAndPreservesInterrupt() {
        val opened = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean()
        lateinit var caller: Thread
        caller = Thread {
            try {
                PcmStreamVoice("interrupted", {
                    opened.countDown()
                    caller.interrupt()
                    object : ByteArrayInputStream(byteArrayOf(0, 64)) {
                        override fun close() { closed.countDown(); super.close() }
                    }
                }, 1).close()
            } catch (_: Exception) {
                interrupted.set(Thread.currentThread().isInterrupted)
            } finally { done.countDown() }
        }
        caller.start()
        assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(opened.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("interrupted startup must close its source", closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(interrupted.get())
    }

    @Test fun lateOpenAfterStartupTimeoutIsClosedByProducer() {
        val opening = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val caller = Thread {
            try {
                PcmStreamVoice("late-open", {
                    opening.countDown()
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    object : ByteArrayInputStream(byteArrayOf(0, 64)) {
                        override fun close() { closed.countDown(); super.close() }
                    }
                }, 1).close()
            } catch (failure: Throwable) { error.set(failure) }
            finally { done.countDown() }
        }
        caller.start()
        try {
            assertTrue(opening.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("startup timeout must bound caller", done.await(8, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(error.get() is IllegalStateException)
        } finally {
            release.countDown()
            caller.join(2000)
            assertTrue("late-opened source closed by owner", closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun rejectsEmptySource() {
        assertThrows(IllegalStateException::class.java) {
            PcmStreamVoice("empty", { ByteArrayInputStream(byteArrayOf()) }, 2)
        }
    }

    @Test fun rejectsOddSource() {
        assertThrows(IllegalStateException::class.java) {
            PcmStreamVoice("odd", { ByteArrayInputStream(byteArrayOf(1)) }, 2)
        }
    }

    @Test fun propagatesStartupOpenError() {
        val error = java.io.IOException("open failed")
        val actual = assertThrows(IllegalStateException::class.java) {
            PcmStreamVoice("error", { throw error }, 2)
        }
        assertSame(error, actual.cause)
    }

    @Test fun closeIsIdempotentAndRenderIsSilent() {
        val voice = PcmStreamVoice("close", { ByteArrayInputStream(byteArrayOf(0, 64)) }, 2)
        voice.close()
        voice.close()
        val out = FloatArray(8) { 1f }
        voice.render(out, out.size, 44100)
        assertArrayEquals(FloatArray(8), out, 0f)
    }

    @Test fun repeatsShortSourceExactly() {
        // Arrange
        val voice = PcmStreamVoice("test", { ByteArrayInputStream(byteArrayOf(0, 64, 0, -64)) }, 8)
        val out = FloatArray(8)
        // Act
        voice.render(out, 8, 44100)
        voice.close()
        // Assert
        assertArrayEquals(floatArrayOf(.5f, -.5f, .5f, -.5f, .5f, -.5f, .5f, -.5f), out, 0f)
    }
}
