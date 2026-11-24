package dev.drewett.noisemachine.audio

import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

/** Single producer/single audio consumer. Only the producer opens and reads sources. */
class PcmStreamVoice(
    override val id: String,
    private val open: () -> InputStream,
    capacityFrames: Int = 16384
) : ProceduralVoice {
    private val ring = FloatArray(capacityFrames.also { require(it > 0) })
    @Volatile private var written = 0L
    @Volatile private var consumed = 0L
    @Volatile private var closed = false
    @Volatile var failure: Throwable? = null
        private set
    private val sourceLock = Any()
    private var currentSource: InputStream? = null
    private val ready = CountDownLatch(1)
    private val worker = thread(name = "PCM-$id", isDaemon = true) {
        try {
            while (!closed) {
                val raw = open()
                synchronized(sourceLock) { currentSource = raw }
                raw.buffered(8192).use { source ->
                    var count = 0
                    while (!closed) {
                        if (written - consumed >= ring.size) {
                            ready.countDown()
                            LockSupport.parkNanos(1_000_000)
                            continue
                        }
                        val lo = source.read()
                        if (lo < 0) {
                            require(count > 0) { "Empty PCM source: $id" }
                            break
                        }
                        val hi = source.read()
                        require(hi >= 0) { "Unaligned PCM source: $id" }
                        ring[(written % ring.size).toInt()] = ((lo or (hi shl 8)).toShort().toInt()) / 32768f
                        written++
                        count++
                    }
                }
            }
        } catch (t: Throwable) {
            if (!closed) failure = t
        } finally {
            synchronized(sourceLock) { currentSource = null }
            ready.countDown()
        }
    }

    init {
        val started = try {
            ready.await(5, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            try { close() } finally { Thread.currentThread().interrupt() }
            throw IllegalStateException("PCM startup interrupted: $id", interrupted)
        }
        if (!started || failure != null || written == 0L) {
            close()
            throw IllegalStateException("PCM startup failed: $id", failure)
        }
    }

    override fun render(out: FloatArray, frames: Int, sampleRate: Int) {
        require(frames in 0..out.size)
        require(sampleRate == 44100)
        for (i in 0 until frames) {
            out[i] = if (!closed && consumed < written) {
                val value = ring[(consumed % ring.size).toInt()]
                consumed++
                value
            } else 0f
        }
    }

    /** Control/test thread only; never call from the audio render callback. */
    internal fun awaitTermination(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0)
        worker.join(timeoutMillis)
        return !worker.isAlive
    }

    override fun close() {
        val source = synchronized(sourceLock) {
            if (closed) return
            closed = true
            currentSource
        }
        worker.interrupt()
        // Never acquire a source/read monitor on the control caller. Android asset
        // streams are finite local reads; arbitrary factories may remain blocked.
        // A late open is closed by the producer's use block, even after cancellation.
        if (source != null) thread(name = "PCM-close-$id", isDaemon = true) {
            try { source.close() } catch (_: Exception) { }
        }
        worker.join(1000)
    }
}
