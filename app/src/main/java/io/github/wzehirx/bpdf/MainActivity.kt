package io.github.wzehirx.bpdf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.wzehirx.bpdf.ui.BPdfNavHost
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BPdfTheme {
                BPdfNavHost()
            }
        }
    }
}
