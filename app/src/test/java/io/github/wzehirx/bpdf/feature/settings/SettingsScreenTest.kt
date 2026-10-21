package io.github.wzehirx.bpdf.feature.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.state.ToggleableState
import io.github.wzehirx.bpdf.core.settings.AppSettings
import io.github.wzehirx.bpdf.core.settings.SaveBehavior
import io.github.wzehirx.bpdf.core.settings.ThemeMode
import io.github.wzehirx.bpdf.ui.theme.BPdfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val spoken = mutableListOf<String>()

    private fun show(
        state: SettingsUiState = SettingsUiState(),
        language: String = "",
        supported: Boolean = true,
    ) = rule.setContent {
        BPdfTheme {
            SettingsScreenContent(
                state = state,
                language = language,
                languageSupported = supported,
                onBack = { calls += "back" },
                onTheme = { calls += "theme:$it" },
                onHighContrast = { calls += "hc:$it" },
                onLanguage = { calls += "lang:$it" },
                onOpenSystemLanguageSettings = { calls += "systemLang" },
                onSaveBehavior = { calls += "save:$it" },
                onClearCache = { calls += "clear" },
                onOpenAbout = { calls += "about" },
                announce = { spoken += it },
            )
        }
    }

    @Test
    fun titleAndSectionsAreHeadings() {
        show()
        listOf("Settings", "Appearance", "Language", "Saving results", "Storage", "About").forEach {
            rule.onNode(hasText(it, substring = false) and isHeading()).assertExists()
        }
    }

    @Test
    fun themeChoices_reflectSelection_andReport() {
        show(SettingsUiState(AppSettings(theme = ThemeMode.DARK)))
        val selected = rule.onNode(isSelectable() and hasText("Dark")).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Selected)
        assertEquals(true, selected)
        rule.onNodeWithText("Light").performScrollTo().performClick()
        assertEquals(listOf("theme:LIGHT"), calls)
    }

    @Test
    fun highContrastSwitch_hasStateText_andToggles() {
        show(SettingsUiState(AppSettings(highContrast = true)))
        val node = rule.onNode(isToggleable() and hasText("High contrast"))
        assertEquals("On", node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(ToggleableState.On, node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        node.performClick()
        assertEquals(listOf("hc:false"), calls)
    }

    @Test
    fun language_choicesCallBack_whenSupported() {
        show(language = "tr")
        assertEquals(true, rule.onNode(isSelectable() and hasText("Türkçe")).fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Selected))
        rule.onNode(isSelectable() and hasText("English")).performScrollTo().performClick()
        assertEquals(listOf("lang:en"), calls)
    }

    @Test
    fun language_unsupported_showsNoteAndSystemSettingsButton() {
        show(supported = false)
        rule.onNodeWithText("Türkçe").assertDoesNotExist()
        rule.onNode(hasText("no per-app language", substring = true)).assertExists()
        rule.onNodeWithText("Open system language settings").performScrollTo().performClick()
        assertEquals(listOf("systemLang"), calls)
    }

    @Test
    fun saveBehavior_choicesCallBack() {
        show(SettingsUiState(AppSettings(saveBehavior = SaveBehavior.ASK)))
        rule.onNodeWithText("Share first").performScrollTo().performClick()
        assertEquals(listOf("save:SHARE"), calls)
    }

    @Test
    fun clearCache_buttonFires_andTheResultIsAnnouncedWithTheFreedSize() {
        show(SettingsUiState(cacheFreedBytes = 2_500_000))
        val text = rule.onNode(hasText("Cache cleared", substring = true)).performScrollTo()
        text.assertExists()
        assertEquals(1, spoken.size)
        assert(spoken.single().startsWith("Cache cleared. Freed ")) { spoken.single() }
        rule.onNodeWithText("Clear cache").performScrollTo().performClick()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun clearCache_whenEmpty_saysSo() {
        show(SettingsUiState(cacheFreedBytes = 0))
        assertEquals(listOf("The cache was already empty."), spoken)
    }

    @Test
    fun clearCache_disabledWhileWorking() {
        show(SettingsUiState(clearingCache = true))
        rule.onNode(hasText("Clear cache") and hasClickAction()).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aboutButtonOpensAbout_andBackWorks() {
        show()
        rule.onNodeWithText("About b PDF").performScrollTo().performClick()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Back")).performClick()
        assertEquals(listOf("about", "back"), calls)
    }
}
