package com.cammonitor.time

import kotlinx.coroutines.delay

interface TimeProvider {
    fun currentTimeMillis(): Long
    suspend fun delayMillis(timeMillis: Long)
}

class DefaultTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override suspend fun delayMillis(timeMillis: Long) = delay(timeMillis)
}
