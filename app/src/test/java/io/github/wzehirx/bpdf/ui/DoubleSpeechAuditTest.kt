package io.github.wzehirx.bpdf.ui

import androidx.compose.material3.Text
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TalkBack must say each thing once. Walks the merged semantics tree of Home, Settings, About, the viewer and every
 * tool screen, empty and with a file chosen, and fails when one element speaks the same phrase twice.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h4000dp")
class DoubleSpeechAuditTest(private val name: String, private val loaded: Boolean, private val index: Int) {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun screenSpeaksEachPhraseOnce() {
        val screen = ScreenFixtures.all(loaded)[index]
        rule.setContent { BPdfTheme { screen.content() } }
        rule.assertNoDoubleSpeech(name)
    }

    @Test
    fun detector_flagsAContentDescriptionOverTheSameVisibleText() {
        if (index != 0 || loaded) return
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "Split PDF. Cut a file." }) {
                Text("Split PDF")
                Text("Cut a file.")
            }
        }
        rule.waitForIdle()
        assertTrue(SpokenText.audit(rule.onRoot().fetchSemanticsNode(), "x").isNotEmpty())
    }

    @Test
    fun detector_acceptsAMergedRowWithoutAContentDescription() {
        if (index != 0 || loaded) return
        rule.setContent {
            androidx.compose.foundation.layout.Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text("Split PDF")
                Text("Cut a file.")
            }
        }
        rule.waitForIdle()
        assertEquals(emptyList<String>(), SpokenText.audit(rule.onRoot().fetchSemanticsNode(), "x"))
    }

    @Test
    fun detector_flagsARepeatedPhrase() {
        if (index != 0 || loaded) return
        assertEquals("page 3 of", SpokenText.repeatedPhrase(listOf("Page 3 of 9, selected", "Page 3 of 9")))
        assertEquals(null, SpokenText.repeatedPhrase(listOf("Choose your name or draw. Then your name or date")))
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = listOf(false, true).flatMap { loaded ->
            ScreenFixtures.all(loaded).mapIndexed { i, e -> arrayOf<Any>(e.name + if (loaded) " (file chosen)" else " (initial)", loaded, i) }
        }
    }
}
