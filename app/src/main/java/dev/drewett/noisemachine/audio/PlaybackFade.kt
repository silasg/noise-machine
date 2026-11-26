package dev.drewett.noisemachine.audio

/** One-second playback envelope; independent of the user's saved volume. */
class PlaybackFade {
    var gain = 1f
        private set
    var running = false
        private set
    private var from = 1f
    private var target = 1f
    private var started = 0L
    private var completion: (() -> Unit)? = null

    fun start(target: Float, now: Long, completion: () -> Unit = {}) {
        from = gain
        this.target = target
        started = now
        this.completion = completion
        running = true
    }

    fun tick(now: Long): Float {
        if (!running) return gain
        val progress = ((now - started) / 1000f).coerceIn(0f, 1f)
        gain = from + (target - from) * progress
        if (progress == 1f) {
            running = false
            val done = completion
            completion = null
            done?.invoke()
        }
        return gain
    }
}
