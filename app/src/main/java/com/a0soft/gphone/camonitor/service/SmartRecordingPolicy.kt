package com.a0soft.gphone.camonitor.service

import java.util.Locale

/** Image labeling indicates scene-level matches; it is not instance detection. */
data class SmartLabel(val text: String, val confidence: Float)

object SmartRecordingPolicy {
    const val SLEEP_MS = 10_000L
    const val PROBE_MS = 4_000L
    const val ABSENCE_GRACE_MS = 4_000L
    const val INFERENCE_SPACING_MS = 700L
    const val MIN_CONFIDENCE = 0.60f

    private val targets = setOf(
        "person", "baby", "crowd", "cat", "dog", "bird", "pet", "bear",
        "gerbil", "horse", "cattle", "bull", "duck", "penguin", "turtle",
        "crocodile", "butterfly", "insect", "waterfowl", "seal", "dalmatian",
        "shetland sheepdog", "basset hound", "cairn terrier", "shikoku",
        "ragdoll", "sphynx", "pixie-bob", "pomeranian"
    )

    fun hasTarget(labels: List<SmartLabel>): Boolean = labels.any { label ->
        label.confidence >= MIN_CONFIDENCE && label.text.lowercase(Locale.ROOT) in targets
    }

    fun targetAbsentForLongEnough(lastSeenAt: Long, now: Long): Boolean =
        lastSeenAt >= 0L && now - lastSeenAt >= ABSENCE_GRACE_MS

    fun restartAfterFinalize(hasError: Boolean, hasTarget: Boolean, resultAt: Long, now: Long): Boolean =
        !hasError && hasTarget && now - resultAt in 0..(2 * INFERENCE_SPACING_MS)
}
