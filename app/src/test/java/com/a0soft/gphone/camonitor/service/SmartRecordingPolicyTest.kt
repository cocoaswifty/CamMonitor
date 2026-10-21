package com.a0soft.gphone.camonitor.service

import org.junit.Assert.*
import org.junit.Test

class SmartRecordingPolicyTest {
    @Test fun targetReturningWhileClipFinalizesStartsNextClip() {
        assertTrue(SmartRecordingPolicy.restartAfterFinalize(false, true, 5_000, 5_200))
        assertFalse(SmartRecordingPolicy.restartAfterFinalize(false, false, 5_000, 5_200))
        assertFalse(SmartRecordingPolicy.restartAfterFinalize(false, true, 1_000, 5_200))
        assertFalse(SmartRecordingPolicy.restartAfterFinalize(true, true, 5_000, 5_200))
    }
    @Test fun triggersOnPeopleAndAnimals() {
        assertTrue(SmartRecordingPolicy.hasTarget(listOf(SmartLabel("Person", .82f))))
        assertTrue(SmartRecordingPolicy.hasTarget(listOf(SmartLabel("Cat", .91f))))
        assertTrue(SmartRecordingPolicy.hasTarget(listOf(SmartLabel("Bird", .70f))))
    }

    @Test fun rejectsLowConfidenceAndUnrelatedLabels() {
        assertFalse(SmartRecordingPolicy.hasTarget(listOf(SmartLabel("Dog", .40f))))
        assertFalse(SmartRecordingPolicy.hasTarget(listOf(SmartLabel("Furniture", .93f))))
        assertFalse(SmartRecordingPolicy.hasTarget(emptyList()))
    }

    @Test fun lossOfDetectionRequiresGracePeriod() {
        assertFalse(SmartRecordingPolicy.targetAbsentForLongEnough(1000, 4999))
        assertTrue(SmartRecordingPolicy.targetAbsentForLongEnough(1000, 5000))
    }
}
