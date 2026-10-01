package com.omarea.util.measure

import kotlin.math.abs

/**
 * Sub-sample aggregation (pure, JVM-testable).
 *
 * One instantaneous read per second aliases fast-moving parameters (CPU/GPU
 * frequency, load, current). The samplers take N quick readings inside the
 * tick and keep the median as the value plus min/max as the noise spread —
 * the spread is the debugging signal (a wide spread = the parameter is
 * jumping, a narrow one = the median is trustworthy).
 *
 * Responsibility: median/min/max over a window of sub-samples.
 * Non-goals: reading nodes (SysReader) and storage (MeasureLog).
 */
object SubsampleMath {

    data class Spread(val median: Double?, val min: Double?, val max: Double?) {
        /** max - min; null when no valid reading. */
        val range: Double? get() =
            if (min != null && max != null) max - min else null
    }

    /**
     * Median/min/max over [values]; `null` readings are ignored, an all-null
     * window yields a [Spread] of nulls (never a sentinel).
     */
    fun spread(values: Collection<Double?>): Spread {
        val sorted = values.filterNotNull().sorted()
        if (sorted.isEmpty()) return Spread(null, null, null)
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        return Spread(median, sorted.first(), sorted.last())
    }

    fun spreadLong(values: Collection<Long?>): Spread =
        spread(values.map { it?.toDouble() })

    /** Median of a long window (nulls ignored). */
    fun medianLong(values: Collection<Long?>): Long? =
        spreadLong(values).median?.toLong()

    /** `abs(a - b)` with null propagation. */
    fun delta(a: Double?, b: Double?): Double? =
        if (a == null || b == null) null else abs(a - b)
}
