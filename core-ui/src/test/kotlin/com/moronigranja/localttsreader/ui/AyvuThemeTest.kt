package com.moronigranja.localttsreader.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Locks the load-bearing B1 palette/token values (guards hex typos and regressions). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AyvuThemeTest {

    @Test
    fun lightThemePrimaryIsBrandAmber() {
        assertEquals(Color(0xFF7A5200), AyvuLightColors.primary)
    }

    @Test
    fun lightThemeBackgroundIsCream() {
        assertEquals(Color(0xFFF5EFE0), AyvuLightColors.background)
    }

    @Test
    fun lightThemeOnBackgroundIsInk() {
        assertEquals(Color(0xFF1B2430), AyvuLightColors.onBackground)
    }

    @Test
    fun darkThemePrimaryIsBrightAmber() {
        assertEquals(Color(0xFFE8A33D), AyvuDarkColors.primary)
    }

    @Test
    fun darkThemeBackgroundIsInk() {
        assertEquals(Color(0xFF1B2430), AyvuDarkColors.background)
    }

    @Test
    fun darkThemeOnBackgroundIsCream() {
        assertEquals(Color(0xFFF5EFE0), AyvuDarkColors.onBackground)
    }

    @Test
    fun largeShapeIs16Dp() {
        assertEquals(RoundedCornerShape(16.dp), AyvuShapes.large)
    }

    @Test
    fun spacingLGIs16Dp() {
        assertEquals(16.dp, AyvuSpacing.LG)
    }

    @Test
    fun motionStandardIs300Ms() {
        assertEquals(300, AyvuMotion.STANDARD_MS)
    }
}