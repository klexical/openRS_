package com.openrs.dash

import com.openrs.dash.data.FuelEconomy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * FuelEconomy is fed from the CAN / demo tick thread while a session or demo-scenario reset runs on
 * another thread. Without a shared lock, reset() could clear the sample window between onUpdate's size
 * check and samples.first() — the NoSuchElementException seen in demo mode (2026-10-24).
 */
class FuelEconomyConcurrencyTest {

    @After fun tearDown() = FuelEconomy.reset()

    @Test
    fun `concurrent updates and resets never throw`() {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val stop = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)
        val start = CountDownLatch(1)
        fun worker(body: (Int) -> Unit) = thread {
            start.await()
            var i = 0
            try { while (System.nanoTime() < stop) body(i++) } catch (t: Throwable) { errors += t }
        }
        val updaters = (0 until 3).map { worker { i -> FuelEconomy.onUpdate(60.0 - (i % 100) * 0.01, 80.0) } }
        val resetter = worker { FuelEconomy.reset() }
        start.countDown()
        (updaters + resetter).forEach { it.join() }
        assertTrue("concurrent access threw: ${errors.firstOrNull()}", errors.isEmpty())
    }

    @Test
    fun `reset leaves a clean state`() {
        repeat(5) { FuelEconomy.onUpdate(60.0, 80.0) }
        FuelEconomy.reset()
        assertEquals(FuelEconomy.EconomyState(), FuelEconomy.state.value)
    }
}
