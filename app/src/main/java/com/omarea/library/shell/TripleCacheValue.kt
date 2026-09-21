package com.omarea.library.shell

import android.content.Context

abstract class TripleCacheValue(private val context: Context, private val storageKey: String) {
    private var cache: String? = null // memory cache
    private val value: String?
        get() {
            if (cache == null) {
                // disk cache
                val storage = context.getSharedPreferences("TripleCacheValues", Context.MODE_PRIVATE)
                if (!storage.contains(storageKey)) {
                    // regenerate the value (usually expensive)
                    cache = initValue()
                    if (cache?.isNotEmpty() == true) {
                        storage.edit().putString(storageKey, cache).apply()
                    }
                } else {
                    cache = storage.getString(storageKey, "")
                }
            }
            return cache
        }

    abstract fun initValue(): String?

    override fun toString(): String {
        val value = this.value
        return value ?: ""
    }

    public fun toInt(): Int {
        val value = this.value
        if (value.isNullOrEmpty()) {
            return 0
        }
        return value.toInt()
    }
}