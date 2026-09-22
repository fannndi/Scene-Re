package com.omarea.vtools.device

import android.content.Context
import android.os.Build
import com.omarea.vtools.R

/**
 * Device support gate.
 *
 * Scene-Re targets a single device: the POCO X3 NFC (surya, SM7150-AC / Snapdragon 732G).
 * The audited MIUI 12/13/14 ROMs report:
 *   ro.product.board=surya, ro.product.vendor.device=surya, ro.product.vendor.model=M2007J20CG,
 *   ro.board.platform=sm6150 (the SoC is actually SM7150-AC).
 *
 * Only public [Build] fields are used here, so the check never spawns a shell and never triggers a
 * root prompt. Supported Android versions are 10 (SDK 29) up to 12 (SDK 31), matching the audited ROMs.
 */
object DeviceSupport {
    const val DEVICE_NAME = "surya"
    const val DEVICE_MODEL = "M2007J20C"
    const val PLATFORM_NAME = "sm6150"
    const val MIN_SDK = Build.VERSION_CODES.Q
    const val MAX_SDK = Build.VERSION_CODES.S

    /** Product name reported by the ROM, e.g. "surya". */
    fun deviceName(): String {
        return listOf(Build.BOARD, Build.DEVICE, Build.PRODUCT)
            .firstOrNull { it.isNotBlank() }
            ?: ""
    }

    /** True when the running ROM belongs to the POCO X3 NFC. */
    fun isSurya(): Boolean {
        return listOf(Build.BOARD, Build.DEVICE, Build.PRODUCT, Build.MODEL).any { value ->
            value.contains(DEVICE_NAME, ignoreCase = true) || value.contains(DEVICE_MODEL, ignoreCase = true)
        }
    }

    /** True for Android 10-12, the versions covered by the ROM audit. */
    fun isSupportedAndroid(): Boolean {
        return Build.VERSION.SDK_INT in MIN_SDK..MAX_SDK
    }

    fun isSupported(): Boolean {
        return isSurya() && isSupportedAndroid()
    }

    /** Localized reason shown when the device is not supported, or null when it is. */
    fun unsupportedReason(context: Context): String? {
        if (!isSurya()) {
            return context.getString(R.string.device_unsupported_device, Build.BOARD, Build.DEVICE, Build.MODEL)
        }
        if (!isSupportedAndroid()) {
            return context.getString(R.string.device_unsupported_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
        }
        return null
    }
}
