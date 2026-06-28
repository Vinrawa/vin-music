package com.vinmusic.recommendation.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BpmEstimatorTest {

    private lateinit var mockedLog: org.mockito.MockedStatic<android.util.Log>

    @org.junit.Before
    fun setUp() {
        mockedLog = org.mockito.Mockito.mockStatic(android.util.Log::class.java)
    }

    @org.junit.After
    fun tearDown() {
        mockedLog.close()
    }

    @Test
    fun `estimateBpm returns zero for empty samples`() {
        val bpm = BpmEstimator.estimateBpm(FloatArray(0), 22050)
        assertEquals(0f, bpm, 0.001f)
    }

    @Test
    fun `estimateBpm estimates tempo of synthetic beat signal within 5 bpm`() {
        val sampleRate = 22050
        val durationSeconds = 30
        val samples = FloatArray(durationSeconds * sampleRate)
        val random = java.util.Random(42) // Seeded for reproducibility
        
        // 1. Fill with low-level baseline noise so the onset detector's dynamic threshold stabilizes
        for (idx in samples.indices) {
            samples[idx] = (random.nextFloat() * 0.01f - 0.005f)
        }
        
        // 2. Generate a percussive beat every 0.5 seconds (120 BPM)
        val beatIntervalSamples = (0.5 * sampleRate).toInt()
        var i = 0
        while (i < samples.size) {
            // Add a short percussive noise burst (50 ms)
            val burstLength = (0.05 * sampleRate).toInt()
            for (j in 0 until burstLength) {
                if (i + j < samples.size) {
                    // Linear decay envelope
                    val envelope = 1.0 - (j.toDouble() / burstLength)
                    val noise = random.nextFloat() * 2f - 1f
                    // Add burst to the baseline noise
                    samples[i + j] = (noise * envelope).toFloat()
                }
            }
            i += beatIntervalSamples
        }

        val bpm = BpmEstimator.estimateBpm(samples, sampleRate)
        println("BpmEstimatorTest: Estimated BPM is $bpm")
        
        // Assert detected BPM is within ±5 of the known synthetic frequency (120 BPM)
        assertEquals(120f, bpm, 5f)
    }
}
