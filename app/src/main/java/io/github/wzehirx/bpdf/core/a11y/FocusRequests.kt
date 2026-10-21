package io.github.wzehirx.bpdf.core.a11y

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Moves focus to the node this requester is attached to, waiting up to a few frames for the node to
 * exist (a page that was just scrolled in is not attached yet when the request is made).
 */
suspend fun FocusRequester.requestFocusWhenReady(attempts: Int = 8) {
    repeat(attempts) {
        val moved = try {
            requestFocus()
        } catch (_: IllegalStateException) {
            false // not attached yet
        }
        if (moved) return
        withFrameNanos { }
    }
}
