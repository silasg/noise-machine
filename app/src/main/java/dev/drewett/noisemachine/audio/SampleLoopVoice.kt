package dev.drewett.noisemachine.audio

/** A mono recording at the mixer's rate; no decoding, I/O or allocation while rendering. */
class SampleLoopVoice(
    override val id: String,
    private val samples: FloatArray,
    private val sourceSampleRate: Int = 44100
) : ProceduralVoice {
    private var position = 0

    init {
        require(sourceSampleRate > 0) { "Sample rate must be positive" }
        require(samples.isNotEmpty()) { "Loop must contain samples" }
        require(samples.all { it.isFinite() && it in -1f..1f }) { "Invalid loop samples" }
    }

    override fun render(out: FloatArray, frames: Int, sampleRate: Int) {
        require(frames in 0..out.size) { "Invalid frame count" }
        require(sampleRate == sourceSampleRate) { "Loop and mixer sample rates must match" }
        for (i in 0 until frames) {
            out[i] = samples[position]
            position++
            if (position == samples.size) position = 0
        }
    }
}

/** Raw mono signed 16-bit little-endian PCM; rate is supplied by the catalog. */
object PcmSamples {
    fun decodeSigned16LittleEndian(bytes: ByteArray): FloatArray {
        require(bytes.isNotEmpty() && bytes.size % 2 == 0) { "Invalid PCM byte length" }
        return FloatArray(bytes.size / 2) { i ->
            val sample = (bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)
            sample / 32768f
        }
    }
}
