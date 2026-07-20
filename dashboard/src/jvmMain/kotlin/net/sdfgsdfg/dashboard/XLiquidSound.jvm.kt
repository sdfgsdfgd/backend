package net.sdfgsdfg.dashboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

private val liquidSoundDispatcher = Dispatchers.IO.limitedParallelism(1)
private val liquidSoundScope = CoroutineScope(SupervisorJob() + liquidSoundDispatcher)

internal actual fun xLiquidSoundPlayer(
    separateUri: String,
    unifyUri: String,
): XLiquidSoundPlayer = JvmLiquidSoundPlayer(separateUri, unifyUri)

private class JvmLiquidSoundPlayer(
    private val separateUri: String,
    private val unifyUri: String,
) : XLiquidSoundPlayer {
    private val clips = mutableMapOf<XLiquidSoundCue, Clip>()
    private var prepared = false
    private var closed = false

    override suspend fun prepare() = withContext(liquidSoundDispatcher) {
        prepareOnAudioLane()
    }

    override suspend fun play(cue: XLiquidSoundCue) = withContext(liquidSoundDispatcher) {
        prepareOnAudioLane()
        ensureActive()
        if (closed) return@withContext
        clips.values.forEach {
            it.stop()
            it.framePosition = 0
        }
        clips[cue]?.start()
    }

    override fun close() {
        liquidSoundScope.launch {
            if (closed) return@launch
            closed = true
            clips.values.forEach(Clip::close)
            clips.clear()
        }
    }

    private fun prepareOnAudioLane() {
        if (prepared || closed) return
        prepared = true
        mapOf(
            XLiquidSoundCue.SEPARATE to separateUri,
            XLiquidSoundCue.UNIFY to unifyUri,
        ).forEach { (cue, uri) ->
            openClip(uri)?.let { clips[cue] = it }
        }
    }

    private fun openClip(uri: String): Clip? = runCatching {
        val clip = AudioSystem.getClip()
        runCatching {
            AudioSystem.getAudioInputStream(URI(uri).toURL()).use(clip::open)
        }.onFailure { clip.close() }.getOrThrow()
        clip
    }.getOrNull()
}
