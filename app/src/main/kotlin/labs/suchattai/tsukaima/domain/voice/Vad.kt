package labs.suchattai.tsukaima.domain.voice

import kotlin.math.max
import kotlin.math.min

/**
 * Tuning of [UtteranceDetector]; all times in milliseconds, levels in 16-bit PCM RMS. A frame is speech when its RMS
 * exceeds `max(speechFactor × noise floor, minSpeechRms)`. The floor is the 25th percentile of the first
 * [noiseFloorFrames] frames, never above [maxNoiseFloorRms] (speech during calibration must not deafen the
 * detector). [minSpeechMs] of speech frames (pauses under [minSpeechMs] allowed) start an utterance; [endSilenceMs]
 * of silence after it, or [maxUtteranceMs] from its first speech frame, end it. [noSpeechTimeoutMs] without an
 * utterance gives up.
 */
data class VadConfig(
    val frameMs: Int = 20,
    val noiseFloorFrames: Int = 15,
    val speechFactor: Double = 2.5,
    val minSpeechRms: Double = 400.0,
    val maxNoiseFloorRms: Double = 300.0,
    val minSpeechMs: Int = 300,
    val endSilenceMs: Int = 1200,
    val noSpeechTimeoutMs: Int = 8000,
    val maxUtteranceMs: Int = 30000,
)

/** What [UtteranceDetector.feed] reports after a frame. */
sealed interface VadEvent {
    /** No utterance yet. */
    data object Listening : VadEvent

    /** An utterance is in progress (including short pauses in it). */
    data object Speech : VadEvent

    /** The utterance ended (trailing silence or the maximum length); every later frame reports this too. */
    data object EndOfUtterance : VadEvent

    /** Nothing was said before the timeout; every later frame reports this too. */
    data object NoSpeech : VadEvent
}

/**
 * Energy-based end-of-utterance detection for one listening run, fed one RMS value per [VadConfig.frameMs] frame.
 * Pure and single-threaded: one detector per run.
 *
 * Speech is recognised from the first frame (the user often starts talking as soon as the reply ends): during
 * calibration the floor is the low percentile of the frames seen so far, capped at [VadConfig.maxNoiseFloorRms].
 * Afterwards non-speech frames quieter than the floor pull it down quickly; louder ones, while no utterance is
 * building, raise it slowly (still capped), so a fan that starts mid-run is absorbed instead of heard as speech.
 */
class UtteranceDetector(val config: VadConfig = VadConfig()) {
    /** Frames fed so far. */
    var frames: Int = 0
        private set

    /** Index of the first frame of the utterance, once one started; the caller trims leading audio relative to it. */
    var speechStartFrame: Int? = null
        private set

    /** The current noise floor (RMS). */
    var noiseFloor: Double = 0.0
        private set

    private val calibration = ArrayList<Double>(config.noiseFloorFrames)
    private var candidateStart: Int? = null
    private var candidateSpeechFrames = 0
    private var silenceRun = 0
    private var done: VadEvent? = null

    private val minSpeechFrames = frames(config.minSpeechMs)
    private val endSilenceFrames = frames(config.endSilenceMs)
    private val noSpeechFrames = frames(config.noSpeechTimeoutMs)
    private val maxUtteranceFrames = frames(config.maxUtteranceMs)

    private fun frames(ms: Int) = ((ms + config.frameMs - 1) / config.frameMs).coerceAtLeast(1)

    fun feed(frameRms: Double): VadEvent {
        done?.let { return it }
        val index = frames++
        val calibrating = index < config.noiseFloorFrames
        if (calibrating) {
            calibration += frameRms
            noiseFloor = min(calibration.sorted()[(calibration.size - 1) / 4], config.maxNoiseFloorRms)
        }
        val speech = frameRms > max(config.speechFactor * noiseFloor, config.minSpeechRms)
        if (!speech && !calibrating) adaptFloor(frameRms)
        val start = speechStartFrame
        if (start == null) {
            if (speech) {
                if (candidateStart == null) candidateStart = index
                candidateSpeechFrames++
                silenceRun = 0
                if (candidateSpeechFrames >= minSpeechFrames) {
                    speechStartFrame = candidateStart
                    return utterance(index, candidateStart!!)
                }
            } else if (candidateStart != null && ++silenceRun >= minSpeechFrames) {
                candidateStart = null
                candidateSpeechFrames = 0
                silenceRun = 0
            }
            return timeout()
        }
        silenceRun = if (speech) 0 else silenceRun + 1
        if (silenceRun >= endSilenceFrames) return VadEvent.EndOfUtterance.also { done = it }
        return utterance(index, start)
    }

    private fun adaptFloor(rms: Double) {
        noiseFloor = when {
            rms < noiseFloor -> noiseFloor * (1 - FLOOR_DECAY) + rms * FLOOR_DECAY
            candidateStart == null && speechStartFrame == null ->
                min(max(noiseFloor, noiseFloor * (1 - FLOOR_RISE) + rms * FLOOR_RISE), config.maxNoiseFloorRms)
            else -> noiseFloor
        }
    }

    private fun utterance(index: Int, start: Int): VadEvent =
        if (index + 1 - start >= maxUtteranceFrames) VadEvent.EndOfUtterance.also { done = it } else VadEvent.Speech

    /** NoSpeech once the timeout passed, but never while an utterance may be starting. */
    private fun timeout(): VadEvent =
        if (frames >= noSpeechFrames && candidateStart == null) VadEvent.NoSpeech.also { done = it } else VadEvent.Listening

    private companion object {
        /** How fast a quieter room lowers the floor, per frame. */
        const val FLOOR_DECAY = 0.05

        /** How fast a louder steady noise raises it, per frame. */
        const val FLOOR_RISE = 0.02
    }
}
