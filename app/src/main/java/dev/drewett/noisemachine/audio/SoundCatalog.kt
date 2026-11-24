package dev.drewett.noisemachine.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for the launchpad sound list.
 * Combines built-in procedural voices, built-in audio assets, and user uploads.
 */
object SoundCatalog {

    data class Sound(
        val id: String,
        val label: String,
        val kind: Kind,
        val source: Source,
        val category: String,
        val builtIn: Boolean
    ) {
        enum class Kind { PROCEDURAL, FILE }
        sealed class Source {
            data class Procedural(val type: String) : Source()
            data class Asset(val assetPath: String) : Source()
            data class PcmStream(val assetPath: String) : Source()
            data class SampleLoop(val assetPath: String, val sampleRate: Int) : Source()
            data class User(val path: String) : Source()
        }
        fun toJson(available: Boolean): JSONObject = JSONObject().apply {
            put("id", id)
            put("label", label)
            put("kind", kind.name)
            put("category", category)
            put("builtIn", builtIn)
            put("available", available)
        }
    }

    private val PROCEDURAL: List<Sound> = listOf(
        Sound("white", "White noise", Sound.Kind.PROCEDURAL,
            Sound.Source.Procedural("white"), "Synthesized", true),
        Sound("pink", "Pink noise", Sound.Kind.PROCEDURAL,
            Sound.Source.Procedural("pink"), "Synthesized", true),
        Sound("brown", "Brown noise", Sound.Kind.PROCEDURAL,
            Sound.Source.Procedural("brown"), "Synthesized", true),
        Sound("clock", "Grandfather clock", Sound.Kind.PROCEDURAL,
            Sound.Source.Procedural("clock"), "Synthesized", true),
    )

    private data class Builtin(
        val id: String, val label: String, val asset: String, val category: String,
        val loopSampleRate: Int? = null
    )

    private val BUILTIN_FILES: List<Builtin> = listOf(
        Builtin("rain", "Rain", "sounds/rain.pcm", "Nature"),
        Builtin("ocean", "Ocean waves", "sounds/ocean.pcm", "Nature"),
        Builtin("wind", "Wind outside", "sounds/wind.pcm", "Nature"),
        Builtin("fan", "Fan", "sounds/fan.pcm", "Indoor", loopSampleRate = 44100),
        Builtin("fireplace", "Fireplace", "sounds/fireplace.pcm", "Indoor"),
        Builtin("storm", "Thunderstorm", "sounds/storm.pcm", "Nature"),
        Builtin("forest", "Forest birds", "sounds/forest.pcm", "Nature"),
        Builtin("stream", "Babbling stream", "sounds/stream.pcm", "Nature"),
        Builtin("cafe", "Coffee shop", "sounds/cafe.pcm", "Indoor"),
        Builtin("ambient", "Ambient pad", "sounds/ambient.pcm", "Music"),
    )

    fun userSoundsDir(ctx: Context): File =
        File(ctx.filesDir, "user_sounds").also { if (!it.exists()) it.mkdirs() }

    fun listAll(ctx: Context): List<Pair<Sound, Boolean>> {
        val out = mutableListOf<Pair<Sound, Boolean>>()
        for (p in PROCEDURAL) out += p to true

        val assetSet: Set<String> = try {
            ctx.assets.list("sounds")?.toSet() ?: emptySet()
        } catch (_: Throwable) { emptySet() }

        for (b in BUILTIN_FILES) {
            val available = assetSet.contains(b.asset.removePrefix("sounds/"))
            val source = b.loopSampleRate?.let { Sound.Source.SampleLoop(b.asset, it) }
                ?: Sound.Source.PcmStream(b.asset)
            out += Sound(b.id, b.label, Sound.Kind.FILE,
                source, b.category, true) to available
        }

        val dir = userSoundsDir(ctx)
        val files = dir.listFiles()?.toList().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in setOf("mp3","ogg","wav","m4a","aac","flac","opus") }
            .sortedBy { it.name }
        for (f in files) {
            val id = "user:${f.name}"
            val label = f.nameWithoutExtension
            out += Sound(id, label, Sound.Kind.FILE,
                Sound.Source.User(f.absolutePath), "Custom", false) to true
        }
        return out
    }

    fun find(ctx: Context, id: String): Sound? =
        listAll(ctx).firstOrNull { it.first.id == id }?.first

    fun toJsonArray(ctx: Context): JSONArray {
        val arr = JSONArray()
        for ((s, available) in listAll(ctx)) arr.put(s.toJson(available))
        return arr
    }
}
