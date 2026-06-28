package com.vinmusic.recommendation.dsp

import android.util.Log

object BpmEstimator {
    private const val TAG = "BpmEstimator"

    /**
     * Estimates the BPM (tempo) of a mono PCM float audio buffer.
     * Uses TarsosDSP PercussionOnsetDetector + BeatRootOnsetEventHandler.
     */
    fun estimateBpm(samples: FloatArray, sampleRate: Int): Float {
        if (samples.isEmpty()) return 0f
        try {
            val bufferSize = 1024
            val overlap = 0
            val eventHandler = be.tarsos.dsp.beatroot.BeatRootOnsetEventHandler()
            val onsetHandler = be.tarsos.dsp.onsets.OnsetHandler { time, salience ->
                val cleanSalience = if (salience <= 0.0) 1.0 else salience
                eventHandler.handleOnset(time, cleanSalience)
            }
            val detector = be.tarsos.dsp.onsets.PercussionOnsetDetector(
                sampleRate.toFloat(),
                bufferSize,
                onsetHandler,
                60.0, // sensitivity
                8.0   // threshold
            )
            
            val format = be.tarsos.dsp.io.TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val audioEvent = be.tarsos.dsp.AudioEvent(format)
            
            var i = 0
            while (i + bufferSize <= samples.size) {
                val buffer = FloatArray(bufferSize)
                System.arraycopy(samples, i, buffer, 0, bufferSize)
                audioEvent.setFloatBuffer(buffer)
                audioEvent.setOverlap(overlap)
                audioEvent.setBytesProcessed(i.toLong() * 2)
                detector.process(audioEvent)
                i += bufferSize
            }
            
            val beatTimes = mutableListOf<Double>()
            eventHandler.trackBeats { time, _ ->
                beatTimes.add(time)
            }
            
            if (beatTimes.size > 1) {
                val intervals = (1 until beatTimes.size).map { beatTimes[it] - beatTimes[it - 1] }
                val avgInterval = intervals.average()
                if (avgInterval > 0) {
                    val calculatedBpm = (60.0 / avgInterval).toFloat()
                    if (calculatedBpm in 40f..250f) {
                        Log.d(TAG, "Estimated BPM: $calculatedBpm (from ${beatTimes.size} beats)")
                        return calculatedBpm
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to estimate BPM using TarsosDSP", e)
        }
        return 0f
    }
}
