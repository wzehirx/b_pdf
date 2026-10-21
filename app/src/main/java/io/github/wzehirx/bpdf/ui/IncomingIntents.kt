package io.github.wzehirx.bpdf.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** What other apps ask this app to open, and the routes the app uses for it. */
object IncomingIntents {
    private const val PDF = "application/pdf"

    /**
     * The PDF an intent asks us to open: the data Uri of an `ACTION_VIEW`, or the stream of an
     * `ACTION_SEND` of `application/pdf`. Only content Uris count. Null for anything else.
     */
    fun pdfUri(intent: Intent?): Uri? {
        intent ?: return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                if (intent.type == PDF) IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) else null
            else -> null
        }
        return uri?.takeIf { it.scheme == "content" }
    }

    /** Route of the viewer, opening [uri] when given. */
    fun viewerRoute(uri: Uri? = null): String =
        if (uri == null) Tool.VIEWER.route else "${Tool.VIEWER.route}?uri=${Uri.encode(uri.toString())}"

    /** Route of [tool], handing it [uri] as its input file when given. */
    fun toolRoute(tool: Tool, uri: Uri? = null): String =
        if (uri == null) tool.route else "${tool.route}?uri=${Uri.encode(uri.toString())}"
}
