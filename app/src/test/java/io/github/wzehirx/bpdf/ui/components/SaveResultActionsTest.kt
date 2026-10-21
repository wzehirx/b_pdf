package io.github.wzehirx.bpdf.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import io.github.wzehirx.bpdf.core.settings.SaveBehavior
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SaveResultActionsTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(behavior: SaveBehavior) = rule.setContent {
        CompositionLocalProvider(LocalSaveBehavior provides behavior) {
            SaveResultActions(onSave = { calls += "save" }, onShare = { calls += "share" })
        }
    }

    private fun buttonOrder() = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map {
        it.config.getOrNull(SemanticsProperties.Text)?.joinToString { t -> t.text }
    }

    @Test
    fun ask_showsSaveFirst() {
        show(SaveBehavior.ASK)
        assertEquals(listOf("Save", "Share"), buttonOrder())
    }

    @Test
    fun share_showsShareFirst_andBothStillWork() {
        show(SaveBehavior.SHARE)
        assertEquals(listOf("Share", "Save"), buttonOrder())
        rule.onNode(hasText("Save") and hasClickAction()).performClick()
        rule.onNode(hasText("Share") and hasClickAction()).performClick()
        assertEquals(listOf("save", "share"), calls)
    }
}
