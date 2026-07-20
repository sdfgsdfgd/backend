package net.sdfgsdfg.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import net.sdfgsdfg.dashboard.generated.resources.Res

private const val LIQUID_SEPARATE_RESOURCE = "files/arcana/liquid-separate.wav"
private const val LIQUID_UNIFY_RESOURCE = "files/arcana/liquid-unify-impact.wav"

internal enum class XLiquidSoundCue { SEPARATE, UNIFY }

internal fun xLiquidSoundCue(wasSeparated: Boolean, isSeparated: Boolean): XLiquidSoundCue? = when {
    !wasSeparated && isSeparated -> XLiquidSoundCue.SEPARATE
    wasSeparated && !isSeparated -> XLiquidSoundCue.UNIFY
    else -> null
}

internal interface XLiquidSoundPlayer {
    suspend fun prepare()
    suspend fun play(cue: XLiquidSoundCue)
    fun close()
}

internal expect fun xLiquidSoundPlayer(
    separateUri: String,
    unifyUri: String,
): XLiquidSoundPlayer

@Composable
internal fun XLiquidSoundEffect(separated: Boolean) {
    val separateUri = remember(LIQUID_SEPARATE_RESOURCE) { Res.getUri(LIQUID_SEPARATE_RESOURCE) }
    val unifyUri = remember(LIQUID_UNIFY_RESOURCE) { Res.getUri(LIQUID_UNIFY_RESOURCE) }
    val player = remember(separateUri, unifyUri) { xLiquidSoundPlayer(separateUri, unifyUri) }
    val currentSeparated = rememberUpdatedState(separated)

    DisposableEffect(player) {
        onDispose(player::close)
    }
    LaunchedEffect(player) {
        player.prepare()
        var previous = currentSeparated.value
        snapshotFlow { currentSeparated.value }.collectLatest { current ->
            val cue = xLiquidSoundCue(previous, current)
            previous = current
            cue?.let { player.play(it) }
        }
    }
}
