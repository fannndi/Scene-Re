package com.omarea.core.profile

/**
 * CPU list math for online/offline masks ("0-3,5" → {0,1,2,3,5}).
 *
 * Pure Kotlin, unit-tested.
 */
object CpuSet {

    fun parse(spec: String): Set<Int> {
        val result = LinkedHashSet<Int>()
        for (part in spec.split(",")) {
            val token = part.trim()
            if (token.isEmpty()) continue
            val range = token.split("-")
            if (range.size == 2) {
                val start = range[0].toIntOrNull() ?: continue
                val end = range[1].toIntOrNull() ?: continue
                if (start <= end) for (cpu in start..end) result.add(cpu)
            } else {
                token.toIntOrNull()?.let { result.add(it) }
            }
        }
        return result
    }

    fun isOnline(spec: String, cpu: Int): Boolean = parse(spec).contains(cpu)
}
