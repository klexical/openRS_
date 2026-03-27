package com.openrs.dash.ui.anim

/**
 * Fixed-capacity ring buffer. push() overwrites the oldest element when full.
 * toList() returns elements oldest-first.
 */
class RingBuffer<T>(val capacity: Int) {

    init { require(capacity > 0) { "RingBuffer capacity must be > 0" } }

    @Suppress("UNCHECKED_CAST")
    private val buffer = arrayOfNulls<Any>(capacity)
    private var head = 0
    private var _size = 0

    val size: Int get() = _size
    val isEmpty: Boolean get() = _size == 0

    fun push(item: T) {
        buffer[head] = item
        head = (head + 1) % capacity
        if (_size < capacity) _size++
    }

    fun toList(): List<T> {
        if (_size == 0) return emptyList()
        val start = if (_size < capacity) 0 else head
        @Suppress("UNCHECKED_CAST")
        return List(_size) { i -> buffer[(start + i) % capacity] as T }
    }

    fun clear() {
        head = 0
        _size = 0
    }
}
