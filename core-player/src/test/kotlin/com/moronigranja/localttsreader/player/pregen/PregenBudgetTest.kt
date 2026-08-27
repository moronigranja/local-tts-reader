package com.moronigranja.localttsreader.player.pregen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CR-1: the wall-clock deadline contract. An absent [PregenBudget.maxTimeMs]
 * means "unbounded" and MUST never be mistaken for an expired deadline — that
 * conflation made whole-book manual pre-generation a false-success no-op.
 */
class PregenBudgetTest {

    @Test
    fun `no deadline never expires`() {
        val budget = PregenBudget()
        assertNull(budget.remainingTimeMs(0L), "unbounded run has no deadline")
        assertNull(budget.remainingTimeMs(1_000_000L), "an unbounded run never expires, however long it runs")
    }

    @Test
    fun `finite deadline reports positive time while running`() {
        assertEquals(500L, PregenBudget(maxTimeMs = 1_000).remainingTimeMs(500))
        assertEquals(1L, PregenBudget(maxTimeMs = 1_000).remainingTimeMs(999))
    }

    @Test
    fun `finite deadline expires at and after the limit`() {
        val budget = PregenBudget(maxTimeMs = 1_000)
        assertEquals(0L, budget.remainingTimeMs(1_000), "exactly at the limit is expired")
        val remaining = budget.remainingTimeMs(1_250)
        assertEquals(-250L, remaining, "past the limit is expired")
        assertTrue(remaining != null && remaining <= 0)
    }

    @Test
    fun `an unbounded budget is not classified as expired`() {
        // The worker breaks ONLY on non-null remaining <= 0; null must not
        // route through the break (the original CR-1 defect).
        assertNull(PregenBudget().remainingTimeMs(9_999))
    }
}