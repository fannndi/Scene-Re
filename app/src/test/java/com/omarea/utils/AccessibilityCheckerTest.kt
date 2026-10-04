package com.omarea.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dumpsys accessibility 的 Bound services 解析。
 *
 * 紧贴真机（POCO X3 / MIUI，Android 12）抓到的真实输出 —— 关键点是
 * Bound services 打印的是 label 而不是 component，早期实现按 component 匹配
 * 会恒为 false-negative。
 */
class AccessibilityCheckerTest {

    private val ourLabel = "Scene - 场景模式"

    // 真机原文：我们的服务已绑定
    private val dumpBound =
        """ACCESSIBILITY MANAGER (dumpsys accessibility)

currentUserId=0
User state[
     attributes:{id=0, touchExplorationEnabled=false}
     Bound services:{Service[label=Scene - 场景模式, feedbackType[FEEDBACK_GENERIC], capabilities=41, eventTypes=[TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOWS_CHANGED], notificationTimeout=0, requestA11yBtn=false]}
     Enabled services:{{com.fannndi.scenere/com.omarea.vtools.AccessibilityScenceMode}}
     Binding services:{}
     Crashed services:{}
]
"""

    // 一个都没绑
    private val dumpUnbound =
        """User state[
     Bound services:{}
     Enabled services:{{com.fannndi.scenere/com.omarea.vtools.AccessibilityScenceMode}}
     Binding services:{}
     Crashed services:{}
]
"""

    // 只绑了别的服务（比如 TalkBack），我们的没连上
    private val dumpOtherOnly =
        """User state[
     Bound services:{Service[label=TalkBack, feedbackType[FEEDBACK_GENERIC], capabilities=41]}
     Enabled services:{{com.fannndi.scenere/com.omarea.vtools.AccessibilityScenceMode}}
     Binding services:{}
     Crashed services:{}
]
"""

    @Test
    fun boundWithOurService_isConnected() {
        assertTrue(AccessibilityChecker.isBoundInAccessibilityDump(dumpBound, ourLabel) == true)
    }

    @Test
    fun emptyBraces_isNotConnected() {
        assertFalse(AccessibilityChecker.isBoundInAccessibilityDump(dumpUnbound, ourLabel) == true)
        assertEquals(false, AccessibilityChecker.isBoundInAccessibilityDump(dumpUnbound, ourLabel))
    }

    @Test
    fun onlySomeOtherServiceBound_isNotConnected() {
        assertEquals(false, AccessibilityChecker.isBoundInAccessibilityDump(dumpOtherOnly, ourLabel))
    }

    @Test
    fun missingLabelFallsBackToHasAnyBinding() {
        assertTrue(AccessibilityChecker.isBoundInAccessibilityDump(dumpOtherOnly, null) == true)
        assertEquals(false, AccessibilityChecker.isBoundInAccessibilityDump(dumpUnbound, null))
    }

    @Test
    fun unknownServiceYieldsNull() {
        assertNull(AccessibilityChecker.isBoundInAccessibilityDump("", ourLabel))
        assertNull(AccessibilityChecker.isBoundInAccessibilityDump("   ", ourLabel))
        assertNull(
            AccessibilityChecker.isBoundInAccessibilityDump(
                "Can't find service: accessibility",
                ourLabel
            )
        )
        // 没有 Bound services 这一段（格式变了 / 拿到的不是 dumpsys）
        assertNull(
            AccessibilityChecker.isBoundInAccessibilityDump(
                "User state[\n     Enabled services:{{x/y}}\n]",
                ourLabel
            )
        )
    }

    @Test
    fun labelIsNotMatchedFromLaterSections() {
        // 后面的段落里出现 label 也不能算已绑定 —— 只看 Bound services 那一行
        val dump =
            """User state[
     Bound services:{}
     Enabled services:{{com.fannndi.scenere/com.omarea.vtools.AccessibilityScenceMode}}
     Client list:{Scene - 场景模式}
]
"""
        assertEquals(false, AccessibilityChecker.isBoundInAccessibilityDump(dump, ourLabel))
    }
}
