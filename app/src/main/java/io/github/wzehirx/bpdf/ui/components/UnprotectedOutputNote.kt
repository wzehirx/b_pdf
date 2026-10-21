package io.github.wzehirx.bpdf.ui.components

import android.content.res.Resources
import io.github.wzehirx.bpdf.R

/**
 * [this] result text, followed (when [show]) by the notice that the saved copy has no password. The notice is part
 * of the one focused result text, so a screen reader speaks it once, without a second live region.
 */
fun String.withUnprotectedNote(resources: Resources, show: Boolean): String =
    if (show) this + " " + resources.getString(R.string.unprotected_output_note) else this
