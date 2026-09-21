package com.omarea.library.calculator

import java.util.*

// calculate time until wake-up
// getUp = hours * 60 + minutes, e.g. 6:30 is 6 * 60 + 30 = 390
class GetUpTime(private val getUp: Int) {
    // current time
    val currentTime: Int
        get() {
            val now = Calendar.getInstance()
            return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        }

    // minutes until next wake-up
    val minutes: Int
        get() {
            val nowTimeValue = currentTime
            // remaining time until wake-up (minutes)
            val timeRemaining = (
                    // similar to computing how long until the next alarm
                    // if today's wake-up time has passed, compute the time until tomorrow's wake-up
                    if (nowTimeValue > getUp) {
                        // (24 * 60) => 1440
                        // (remaining time today + tomorrow's wake-up time) / 60 minutes to get hours
                        ((1440 - nowTimeValue) + getUp)
                    }
                    // if today's wake-up time has not passed yet
                    else {
                        (getUp - nowTimeValue)
                    })
            return timeRemaining
        }

    val nextGetUpTime: Long
        get() {
            return System.currentTimeMillis() + (minutes * 60 * 1000)
        }
}