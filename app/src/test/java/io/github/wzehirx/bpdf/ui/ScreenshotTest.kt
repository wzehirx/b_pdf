package io.github.wzehirx.bpdf.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.drawToBitmap
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders screens to PNG files for a visual check. Does nothing unless the environment variable BPDF_SCREENSHOTS names
 * the output directory, so the normal test run is not slowed down and writes nothing:
 * `BPDF_SCREENSHOTS=/path ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
class ScreenshotTest(private val screen: String, private val loaded: Boolean, private val dark: Boolean) {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun render() {
        val dir = System.getenv("BPDF_SCREENSHOTS")
        assumeTrue("BPDF_SCREENSHOTS is not set", !dir.isNullOrBlank())
        val entry = ScreenFixtures.all(loaded).first { it.name == screen }
        rule.setContent { BPdfTheme(darkTheme = dark) { entry.content() } }
        rule.waitForIdle()
        // captureToImage waits for a real frame, which Robolectric never delivers; drawing the window view works.
        val bitmap = rule.activity.window.decorView.drawToBitmap()
        val name = screen.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val file = File(dir, "$name-${if (loaded) "file" else "empty"}-${if (dark) "dark" else "light"}.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    companion object {
        private val shown = listOf(
            "Home" to false, "Home" to true, "Split" to false, "Split" to true, "Merge" to false, "Merge" to true,
            "Watermark" to true, "Settings" to false, "Protect" to true,
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1} {2}")
        fun params(): List<Array<Any>> = shown.flatMap { (s, loaded) -> listOf(false, true).map { arrayOf<Any>(s, loaded, it) } }
    }
}
