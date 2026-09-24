package com.omarea.ui

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.util.Locale

/**
 * Shared text-highlighting helper for the list adapters.
 *
 * Five adapters (`AdapterAppList`, `AdapterProcess`, `AdapterProcessMini`,
 * `AdapterSceneMode`, `fps/AdapterSessions`) each carried their own copy of the
 * same `keywordHighLight` / `keywordHightLight` routine, including a hard-coded
 * `"#0094ff"`. The colour and the case-folding rule now live in one place so a
 * palette change cannot leave four adapters behind.
 */
object SearchHighlighter {
    /** Accent used to mark the matched substring. */
    private const val HIGHLIGHT_COLOR = "#0094ff"

    /**
     * Returns [text] with the first occurrence of [keywords] highlighted.
     *
     * Matching is case-insensitive and respects the default locale, matching the
     * previous per-adapter behaviour. An empty [keywords], or one that does not
     * occur in [text], returns the plain [SpannableString] unchanged.
     */
    @JvmStatic
    fun highlight(text: String, keywords: String): SpannableString {
        val spannableString = SpannableString(text)
        if (keywords.isEmpty()) {
            return spannableString
        }

        val locale = Locale.getDefault()
        val index = text.lowercase(locale).indexOf(keywords.lowercase(locale))
        if (index < 0) {
            return spannableString
        }

        spannableString.setSpan(
            ForegroundColorSpan(Color.parseColor(HIGHLIGHT_COLOR)),
            index,
            index + keywords.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannableString
    }
}
