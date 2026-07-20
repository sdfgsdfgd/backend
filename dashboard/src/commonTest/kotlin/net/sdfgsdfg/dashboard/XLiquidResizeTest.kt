package net.sdfgsdfg.dashboard

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XLiquidResizeTest {
    @Test
    fun resizeStrainStaysDirectionalMonotonicAndAsymptotic() {
        assertEquals(0f, xLiquidResizeStrain(220f, 220f))

        val mildExpansion = xLiquidResizeStrain(200f, 220f)
        val strongExpansion = xLiquidResizeStrain(50f, 220f)
        val contraction = xLiquidResizeStrain(220f, 50f)

        assertTrue(mildExpansion > 0f)
        assertTrue(strongExpansion > mildExpansion)
        assertTrue(contraction < 0f)
        assertTrue(maxOf(strongExpansion, abs(contraction)) < 0.18f)
    }
}
