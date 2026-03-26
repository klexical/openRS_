package com.openrs.dash

import com.openrs.dash.ui.anim.RingBuffer
import org.junit.Assert.*
import org.junit.Test

class RingBufferTest {

    // ── Empty state ─────────────────────────────────────────────────────────

    @Test
    fun `empty buffer returns empty list`() {
        val buf = RingBuffer<Int>(5)
        assertEquals(0, buf.size)
        assertTrue(buf.isEmpty)
        assertEquals(emptyList<Int>(), buf.toList())
    }

    // ── Push within capacity ────────────────────────────────────────────────

    @Test
    fun `push single element`() {
        val buf = RingBuffer<Int>(5)
        buf.push(42)
        assertEquals(1, buf.size)
        assertFalse(buf.isEmpty)
        assertEquals(listOf(42), buf.toList())
    }

    @Test
    fun `push up to capacity preserves insertion order`() {
        val buf = RingBuffer<Int>(4)
        buf.push(1); buf.push(2); buf.push(3); buf.push(4)
        assertEquals(4, buf.size)
        assertEquals(listOf(1, 2, 3, 4), buf.toList())
    }

    // ── Wraparound ──────────────────────────────────────────────────────────

    @Test
    fun `push beyond capacity drops oldest`() {
        val buf = RingBuffer<Int>(3)
        buf.push(1); buf.push(2); buf.push(3)
        buf.push(4)
        assertEquals(3, buf.size)
        assertEquals(listOf(2, 3, 4), buf.toList())
    }

    @Test
    fun `double wraparound returns correct order`() {
        val buf = RingBuffer<Int>(3)
        for (i in 1..9) buf.push(i)
        assertEquals(3, buf.size)
        assertEquals(listOf(7, 8, 9), buf.toList())
    }

    // ── Capacity of 1 ───────────────────────────────────────────────────────

    @Test
    fun `capacity 1 always holds last pushed element`() {
        val buf = RingBuffer<String>(1)
        buf.push("a"); buf.push("b"); buf.push("c")
        assertEquals(1, buf.size)
        assertEquals(listOf("c"), buf.toList())
    }

    // ── Clear ───────────────────────────────────────────────────────────────

    @Test
    fun `clear resets to empty`() {
        val buf = RingBuffer<Int>(5)
        buf.push(1); buf.push(2); buf.push(3)
        buf.clear()
        assertEquals(0, buf.size)
        assertTrue(buf.isEmpty)
        assertEquals(emptyList<Int>(), buf.toList())
    }

    @Test
    fun `push after clear works correctly`() {
        val buf = RingBuffer<Int>(3)
        buf.push(1); buf.push(2); buf.push(3)
        buf.clear()
        buf.push(10); buf.push(20)
        assertEquals(2, buf.size)
        assertEquals(listOf(10, 20), buf.toList())
    }

    // ── Generic types ───────────────────────────────────────────────────────

    @Test
    fun `works with Pair type`() {
        val buf = RingBuffer<Pair<Float, Float>>(2)
        buf.push(0.5f to -0.3f)
        buf.push(1.0f to 0.8f)
        buf.push(-0.2f to 0.1f)
        assertEquals(listOf(1.0f to 0.8f, -0.2f to 0.1f), buf.toList())
    }

    // ── Invalid capacity ──────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `capacity zero throws IllegalArgumentException`() {
        RingBuffer<Int>(0)
    }
}
