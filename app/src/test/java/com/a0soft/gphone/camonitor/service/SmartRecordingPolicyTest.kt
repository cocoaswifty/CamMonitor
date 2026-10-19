package com.a0soft.gphone.camonitor.service

import org.junit.Assert.*
import org.junit.Test

class SmartRecordingPolicyTest {
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
