package net.sdfgsdfg.dashboard

import org.w3c.dom.Audio
import org.w3c.dom.HTMLAudioElement
import kotlin.js.ExperimentalWasmJsInterop

internal actual fun xLiquidSoundPlayer(
    separateUri: String,
    unifyUri: String,
): XLiquidSoundPlayer = WasmLiquidSoundPlayer(separateUri, unifyUri)

@OptIn(ExperimentalWasmJsInterop::class)
private class WasmLiquidSoundPlayer(
    private val separateUri: String,
    private val unifyUri: String,
) : XLiquidSoundPlayer {
    private var tracks: Map<XLiquidSoundCue, HTMLAudioElement>? = null
    private var closed = false

    override suspend fun prepare() {
        if (tracks != null || closed) return
        tracks = mapOf(
            XLiquidSoundCue.SEPARATE to Audio(separateUri),
            XLiquidSoundCue.UNIFY to Audio(unifyUri),
        ).onEach { (_, audio) ->
            audio.preload = "auto"
            audio.load()
        }
    }

    override suspend fun play(cue: XLiquidSoundCue) {
        prepare()
        if (closed) return
        tracks?.values?.forEach {
            it.pause()
            it.currentTime = 0.0
        }
        tracks?.get(cue)?.play()?.catch { null }
    }

    override fun close() {
        if (closed) return
        closed = true
        tracks?.values?.forEach {
            it.pause()
            it.removeAttribute("src")
            it.load()
        }
        tracks = null
    }
}
