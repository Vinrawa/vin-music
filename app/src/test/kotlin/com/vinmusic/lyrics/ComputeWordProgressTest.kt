package com.vinmusic.lyrics

import org.junit.Assert.*
import org.junit.Test

class ComputeWordProgressTest {

    @Test
    fun `empty words returns 0, 0f`() {
        val (idx, fill) = computeWordProgress(emptyList(), 5000L)
        assertEquals(0, idx)
        assertEquals(0f, fill)
    }

    @Test
    fun `position before first word returns first word with 0 fill`() {
        val words = listOf(
            WordTiming("Hello", 1000L, 2000L),
            WordTiming("World", 2000L, 3000L)
        )
        val (idx, fill) = computeWordProgress(words, 500L)
        assertEquals(0, idx)
        assertEquals(0f, fill)
    }

    @Test
    fun `position at word start returns that word with 0 fill`() {
        val words = listOf(
            WordTiming("Hello", 1000L, 2000L),
            WordTiming("World", 2000L, 3000L)
        )
        val (idx, fill) = computeWordProgress(words, 1000L)
        assertEquals(0, idx)
        assertEquals(0f, fill)
    }

    @Test
    fun `position at word boundary selects next word`() {
        val words = listOf(
            WordTiming("Hello", 1000L, 2000L),
            WordTiming("World", 2000L, 3000L)
        )
        val (idx, fill) = computeWordProgress(words, 2000L)
        assertEquals(1, idx)
        assertEquals(0f, fill)
    }

    @Test
    fun `position just before word end returns high fill`() {
        val words = listOf(
            WordTiming("Hello", 1000L, 2000L),
            WordTiming("World", 2000L, 3000L)
        )
        val (idx, fill) = computeWordProgress(words, 1999L)
        assertEquals(0, idx)
        assertEquals(0.999f, fill, 0.001f)
    }

    @Test
    fun `position mid-word returns correct fraction`() {
        val words = listOf(
            WordTiming("Hello", 1000L, 2000L),
            WordTiming("World", 2000L, 3000L)
        )
        val (idx, fill) = computeWordProgress(words, 1500L)
        assertEquals(0, idx)
        assertEquals(0.5f, fill)
    }

    @Test
    fun `position between words selects later word`() {
        val words = listOf(
            WordTiming("Hello", 1000L, 2000L),
            WordTiming("World", 2000L, 3000L)
        )
        val (idx, fill) = computeWordProgress(words, 2500L)
        assertEquals(1, idx)
        assertEquals(0.5f, fill)
    }

    @Test
    fun `position after all words returns last word with 1f fill`() {
        val words = listOf(
            WordTiming("Hello", 1000L, 2000L),
            WordTiming("World", 2000L, 3000L)
        )
        val (idx, fill) = computeWordProgress(words, 5000L)
        assertEquals(1, idx)
        assertEquals(1f, fill)
    }

    @Test
    fun `zero duration word clamps to 0`() {
        val words = listOf(
            WordTiming("Hello", 1000L, 1000L)
        )
        val (idx, fill) = computeWordProgress(words, 1000L)
        assertEquals(0, idx)
        assertEquals(0f, fill)
    }

    @Test
    fun `single word fills correctly`() {
        val words = listOf(
            WordTiming("Solo", 0L, 5000L)
        )
        val (idx, fill) = computeWordProgress(words, 2500L)
        assertEquals(0, idx)
        assertEquals(0.5f, fill)
    }
}
