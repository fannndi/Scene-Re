package com.omarea.library.calculator

import java.util.*

// Calculates how long until it is time to get up
// getUp = hours * 60 + minutes, e.g. 6:30 is 6 * 60 + 30 = 390
class GetUpTime(private val getUp: Int) {
    // current time
    val currentTime: Int
        get() {
            val now = Calendar.getInstance()
            return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        }

    // minutes until the next get-up time
    val minutes: Int
        get() {
            val nowTimeValue = currentTime
            // remaining time until get-up (minutes)
            val timeRemaining = (
                    // Similar to computing how long until the next alarm rings
                    // If today's get-up time has passed, count the time until tomorrow's get-up
                    if (nowTimeValue > getUp) {
                        // (24 * 60) => 1440
                        // (remaining time today + tomorrow's get-up time) / 60 minutes gives the hours
                        ((1440 - nowTimeValue) + getUp)
                    }
                    // If today's get-up time has not passed yet
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