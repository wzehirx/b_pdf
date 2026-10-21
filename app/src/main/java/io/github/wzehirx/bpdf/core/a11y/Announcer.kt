package io.github.wzehirx.bpdf.core.a11y

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Returns a function that makes TalkBack (and other services) speak a message. */
@Composable
fun rememberAnnouncer(): (String) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { message: String ->
            // announceForAccessibility is deprecated in API 36 but remains the most reliable
            // way to speak a one-off status message across all supported API levels.
            @Suppress("DEPRECATION")
            view.announceForAccessibility(message)
        }
    }
}
