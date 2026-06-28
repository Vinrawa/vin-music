package com.vinmusic.player

import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class EightDAudioProcessor : BaseAudioProcessor() {
    var enabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                flush()
            }
        }

    private var theta = 0.0
    private val rotationHz = 0.28 // Clear left-right orbit without turning into tremolo.
    private var sourceDelay = DoubleArray(1)
    private var lowPassDelay = DoubleArray(1)
    private var writeIndex = 0
    private var lowPassState = 0.0
    private var configuredSampleRate = 44_100

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        configuredSampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        resizeDelayLines(configuredSampleRate)
        return inputAudioFormat
    }

    override fun onFlush() {
        clearState()
    }

    override fun onReset() {
        sourceDelay = DoubleArray(1)
        lowPassDelay = DoubleArray(1)
        clearState()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        if (!enabled) {
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channelCount = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate

        if (channelCount == 2) {
            if (sampleRate != configuredSampleRate || sourceDelay.size <= 1) {
                configuredSampleRate = sampleRate.coerceAtLeast(1)
                resizeDelayLines(configuredSampleRate)
            }

            val outputBuffer = replaceOutputBuffer(remaining)
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

            val limit = inputBuffer.limit()
            while (inputBuffer.position() + 3 < limit) {
                val leftVal = inputBuffer.getShort().toDouble()
                val rightVal = inputBuffer.getShort().toDouble()
                val mono = (leftVal + rightVal) * 0.5
                val side = (leftVal - rightVal) * 0.5

                lowPassState += 0.18 * (mono - lowPassState)
                sourceDelay[writeIndex] = mono
                lowPassDelay[writeIndex] = lowPassState

                val pan = sin(theta) // -1 = left, +1 = right.
                val frontBack = cos(theta) // +1 = front, -1 = rear.
                val maxItdSamples = (sampleRate * 0.00115).coerceAtLeast(1.0)
                val itd = pan * maxItdSamples
                val leftDelay = if (itd > 0.0) itd else 0.0
                val rightDelay = if (itd < 0.0) -itd else 0.0

                val leftSource = readDelay(sourceDelay, leftDelay)
                val rightSource = readDelay(sourceDelay, rightDelay)
                val leftShadow = readDelay(lowPassDelay, leftDelay)
                val rightShadow = readDelay(lowPassDelay, rightDelay)

                val leftGain = sqrt(((1.0 - pan) * 0.5).coerceIn(0.02, 1.0))
                val rightGain = sqrt(((1.0 + pan) * 0.5).coerceIn(0.02, 1.0))
                val leftNearBoost = 1.0 + ((-pan).coerceAtLeast(0.0) * 0.20)
                val rightNearBoost = 1.0 + (pan.coerceAtLeast(0.0) * 0.20)

                val rightSide = pan.coerceAtLeast(0.0)
                val leftSide = (-pan).coerceAtLeast(0.0)
                val rearDistance = ((1.0 - frontBack) * 0.5).coerceIn(0.0, 1.0)
                val leftShadowMix = (0.62 * rightSide + 0.22 * rearDistance).coerceIn(0.0, 0.88)
                val rightShadowMix = (0.62 * leftSide + 0.22 * rearDistance).coerceIn(0.0, 0.88)
                val leftHeadShadow = leftSource * (1.0 - leftShadowMix) + leftShadow * leftShadowMix
                val rightHeadShadow = rightSource * (1.0 - rightShadowMix) + rightShadow * rightShadowMix

                val roomDelayA = sampleRate * 0.014
                val roomDelayB = sampleRate * 0.031
                val roomDelayC = sampleRate * 0.049
                val roomA = readDelay(sourceDelay, roomDelayA) * 0.014
                val roomB = readDelay(sourceDelay, roomDelayB) * 0.010
                val roomC = readDelay(lowPassDelay, roomDelayC) * 0.006
                val roomWidth = 0.5 + 0.42 * frontBack
                val distanceGain = 1.0 - (0.16 * rearDistance)
                val sideBlend = 0.12 * (1.0 - abs(pan) * 0.45)

                val spatialL = (
                    leftHeadShadow * leftGain * leftNearBoost +
                        side * sideBlend +
                        roomA * (1.0 - roomWidth) +
                        roomB * roomWidth +
                        roomC * rearDistance
                    ) * distanceGain
                val spatialR = (
                    rightHeadShadow * rightGain * rightNearBoost -
                        side * sideBlend +
                        roomA * roomWidth +
                        roomB * (1.0 - roomWidth) +
                        roomC * rearDistance
                    ) * distanceGain

                val dryBlend = 0.015
                val wetBlend = 0.985
                val outL = leftVal * dryBlend + spatialL * wetBlend
                val outR = rightVal * dryBlend + spatialR * wetBlend

                outputBuffer.putShort(softClipToShort(outL))
                outputBuffer.putShort(softClipToShort(outR))

                writeIndex++
                if (writeIndex >= sourceDelay.size) writeIndex = 0

                theta += (2.0 * PI * rotationHz) / sampleRate
                if (theta > 2.0 * PI) {
                    theta -= 2.0 * PI
                }
            }
            inputBuffer.position(limit)
            outputBuffer.flip()
        } else {
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
        }
    }

    private fun resizeDelayLines(sampleRate: Int) {
        val frames = (sampleRate * 0.08).coerceAtLeast(128.0).toInt()
        sourceDelay = DoubleArray(frames)
        lowPassDelay = DoubleArray(frames)
        clearState()
    }

    private fun clearState() {
        theta = -PI / 2.0
        writeIndex = 0
        lowPassState = 0.0
        sourceDelay.fill(0.0)
        lowPassDelay.fill(0.0)
    }

    private fun readDelay(buffer: DoubleArray, delaySamples: Double): Double {
        if (buffer.isEmpty()) return 0.0
        val safeDelay = delaySamples.coerceIn(0.0, (buffer.size - 2).coerceAtLeast(0).toDouble())
        val baseDelay = safeDelay.toInt()
        val fraction = safeDelay - baseDelay
        val indexA = floorMod(writeIndex - baseDelay, buffer.size)
        val indexB = floorMod(indexA - 1, buffer.size)
        return buffer[indexA] * (1.0 - fraction) + buffer[indexB] * fraction
    }

    private fun floorMod(value: Int, mod: Int): Int {
        val result = value % mod
        return if (result < 0) result + mod else result
    }

    private fun softClipToShort(value: Double): Short {
        val normalized = (value / 32768.0).coerceIn(-2.0, 2.0)
        val magnitude = abs(normalized)
        val clipped = if (magnitude <= 0.96) {
            normalized
        } else {
            val softened = 0.96 + ((magnitude - 0.96) / (1.0 + magnitude - 0.96)) * 0.04
            if (normalized < 0.0) -softened else softened
        }
        return (clipped * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
    }
}
