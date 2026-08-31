package com.moronigranja.localttsreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Two-tone horizontal progress bar (decisions #94; legend recolored #95),
 * 4.dp tall — the M3 indicator height. Color legend: teal `primary` =
 * listened, amber `secondary` = generated but not yet listened,
 * `surfaceVariant` track = remaining. No M3 component renders two
 * segments, which is why the custom bar exists. The generated segment never
 * paints over the played one: callers clamp fractions so
 * playedFraction + generatedFraction ≤ 1.
 */
@Composable
fun SegmentedProgress(playedFraction: Float, generatedFraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxSize()) {
            // RowScope.weight rejects 0 — segments that measure zero are
            // simply not emitted (played=0/no pregen and book-end both occur).
            // Segments must fillMaxHeight: a bare weighted Box wraps to 0.dp
            // tall and its background paints nothing (found on device, #95).
            if (playedFraction > 0f) {
                Box(Modifier.fillMaxHeight().weight(playedFraction).background(MaterialTheme.colorScheme.primary))
            }
            if (generatedFraction > 0f) {
                Box(Modifier.fillMaxHeight().weight(generatedFraction).background(MaterialTheme.colorScheme.secondary))
            }
            val empty = (1f - playedFraction - generatedFraction).coerceAtLeast(0f)
            if (empty > 0f) {
                Box(Modifier.fillMaxHeight().weight(empty))
            }
        }
    }
}
