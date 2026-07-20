package net.sdfgsdfg.dashboard

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XComposerTest {
    @Test
    fun modeIsDerivedFromMeaningfulInputWithAudioAsTheOnlyOverride() {
        listOf("", "   \n", "... — _").forEach {
            assertEquals(XComposerMode.IDLE, xComposerMode(it, audioActive = false))
        }
        listOf("a", "7", "  idea! ", "東京").forEach {
            assertEquals(XComposerMode.TEXT, xComposerMode(it, audioActive = false))
        }
        assertEquals(XComposerMode.AUDIO, xComposerMode("draft remains", audioActive = true))
    }

    @Test
    fun soundFollowsOnlyActualLiquidTopologyEdges() {
        assertEquals(XLiquidSoundCue.SEPARATE, xLiquidSoundCue(wasSeparated = false, isSeparated = true))
        assertEquals(XLiquidSoundCue.UNIFY, xLiquidSoundCue(wasSeparated = true, isSeparated = false))
        assertEquals(null, xLiquidSoundCue(wasSeparated = false, isSeparated = false))
        assertEquals(null, xLiquidSoundCue(wasSeparated = true, isSeparated = true))
    }

    @Test
    fun ctrlJInsertionReplacesTheSelectionAndMovesTheCaretOnce() {
        assertEquals(
            TextFieldValue("before\nafter", TextRange(7)),
            TextFieldValue("beforeafter", TextRange(6)).xInsertNewline(),
        )
        assertEquals(
            TextFieldValue("a\nd", TextRange(2)),
            TextFieldValue("abcd", TextRange(3, 1)).xInsertNewline(),
        )
    }

    @Test
    fun actionDropletMovesFromInsideTheAnimatedBodyToItsSplitPosition() {
        val merged = xLiquidActionLeft(36f, 220f, 64f, 18f, separation = 0f)
        val neck = xLiquidActionLeft(36f, 220f, 64f, 18f, separation = 0.5f)
        val split = xLiquidActionLeft(36f, 220f, 64f, 18f, separation = 1f)

        assertEquals(192f, merged)
        assertEquals(274f, split)
        assertTrue(neck in merged..split)
        assertTrue(neck - merged < split - neck, "the droplet should load tension before it releases")
    }

    @Test
    fun actionOvershootRemainsLiveButAsymptoticallyBounded() {
        val merged = xLiquidActionLeft(36f, 220f, 64f, 18f, separation = 0f)
        val split = xLiquidActionLeft(36f, 220f, 64f, 18f, separation = 1f)
        val mildOvershoot = xLiquidActionLeft(36f, 220f, 64f, 18f, separation = 1.12f)
        val strongOvershoot = xLiquidActionLeft(36f, 220f, 64f, 18f, separation = 1.30f)
        val extremeOvershoot = xLiquidActionLeft(36f, 220f, 64f, 18f, separation = 100f)

        assertTrue(mildOvershoot < strongOvershoot)
        assertTrue(strongOvershoot < extremeOvershoot)
        assertTrue(extremeOvershoot < split + (split - merged) * 0.11f)
    }

    @Test
    fun actionContentAppearsOnlyAfterTheDropletHasEmerged() {
        assertEquals(0f, xLiquidActionContentAlpha(0.56f))
        assertTrue(xLiquidActionContentAlpha(0.66f) in 0f..1f)
        assertEquals(1f, xLiquidActionContentAlpha(0.76f))
    }

    @Test
    fun elongatedFluidProfileRoundsAtRestAndSoftensUnderPressure() {
        assertEquals(2f, xLiquidProfileExponent(aspect = 1f, tension = 0f, eigenmode = 0f))
        assertEquals(2f, xLiquidProfileExponent(aspect = 1f, tension = 1f, eigenmode = 1f))

        val resting = xLiquidProfileExponent(aspect = 1.75f, tension = 0f, eigenmode = 0f)
        val pressured = xLiquidProfileExponent(aspect = 1.75f, tension = 1f, eigenmode = 0.5f)
        assertTrue(abs(resting - 3.4f) < 0.0001f)
        assertTrue(pressured in 2.85f..3f)
        assertTrue(pressured < resting)
    }

    @Test
    fun fluidDeformationIsAreaConsciousWhileRigidActionsRemainStable() {
        assertEquals(
            XLiquidDeformation(1f, 1f, 0f, 0f),
            xLiquidDeformation(1.24f, 1f, fluidAction = false, merging = false),
        )

        val loaded = xLiquidDeformation(0.5f, 0.5f, fluidAction = true, merging = false)
        val recoiling = xLiquidDeformation(1.18f, 1f, fluidAction = true, merging = false)
        assertTrue(loaded.scaleX > 1f && loaded.scaleY < 1f)
        assertTrue(recoiling.scaleX < 1f && recoiling.scaleY > 1f)
        assertTrue(abs(loaded.scaleX * loaded.scaleY - 0.94f * 0.94f) < 0.0001f)
        assertTrue(abs(recoiling.scaleX * recoiling.scaleY - 1f) < 0.0001f)
    }
}
