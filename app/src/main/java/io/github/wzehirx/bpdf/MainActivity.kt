package io.github.wzehirx.bpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import io.github.wzehirx.bpdf.ui.BPdfNavHost
import io.github.wzehirx.bpdf.ui.HOME_ROUTE
import io.github.wzehirx.bpdf.ui.IncomingIntents
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import kotlinx.coroutines.channels.Channel

class MainActivity : ComponentActivity() {
    // PDFs other apps asked us to open; consumed by the navigation graph.
    private val incoming = Channel<Uri>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a rotation or other recreation the same intent is delivered again: handle it only once.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            BPdfTheme {
                val navController = rememberNavController()
                LaunchedEffect(navController) {
                    for (uri in incoming) {
                        // Back from the viewer goes home; a second request replaces the first.
                        navController.navigate(IncomingIntents.viewerRoute(uri)) {
                            popUpTo(HOME_ROUTE)
                        }
                    }
                }
                BPdfNavHost(navController)
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = IncomingIntents.pdfUri(intent) ?: return
        if (intent?.action == Intent.ACTION_VIEW) {
            // Keep access for the recent files list, but only when the sender allows it.
            (application as BPdfApp).container.fileRepository.tryPersistReadPermission(uri, intent.flags)
        }
        incoming.trySend(uri)
    }
}
