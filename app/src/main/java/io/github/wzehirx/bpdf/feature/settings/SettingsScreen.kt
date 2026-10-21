package io.github.wzehirx.bpdf.feature.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.core.settings.AppLanguage
import io.github.wzehirx.bpdf.core.settings.SaveBehavior
import io.github.wzehirx.bpdf.core.settings.ThemeMode
import io.github.wzehirx.bpdf.ui.components.ChoiceRow
import io.github.wzehirx.bpdf.ui.components.SwitchRow
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The language is owned by the system (LocaleManager); keep the choice here so the radio updates at once.
    var language by rememberSaveable { mutableStateOf(AppLanguage.current(context)) }
    SettingsScreenContent(
        state = state,
        language = language,
        languageSupported = AppLanguage.isSupported,
        onBack = onBack,
        onTheme = viewModel::setTheme,
        onHighContrast = viewModel::setHighContrast,
        onLanguage = { language = it; AppLanguage.set(context, it) },
        onOpenSystemLanguageSettings = {
            try { context.startActivity(AppLanguage.systemLanguageSettingsIntent()) } catch (_: Exception) {}
        },
        onSaveBehavior = viewModel::setSaveBehavior,
        onClearCache = viewModel::clearCache,
        onOpenAbout = onOpenAbout,
    )
}

@Composable
fun SettingsScreenContent(
    state: SettingsUiState,
    language: String,
    languageSupported: Boolean,
    onBack: () -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onHighContrast: (Boolean) -> Unit,
    onLanguage: (String) -> Unit,
    onOpenSystemLanguageSettings: () -> Unit,
    onSaveBehavior: (SaveBehavior) -> Unit,
    onClearCache: () -> Unit,
    onOpenAbout: () -> Unit,
    announce: (String) -> Unit = rememberAnnouncer(),
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val freed = state.cacheFreedBytes
    val freedText = freed?.let {
        if (it == 0L) resources.getString(R.string.settings_cache_empty)
        else resources.getString(R.string.settings_cache_cleared, Formatter.formatShortFileSize(context, it))
    }
    // Speak the result of Clear cache once (not also as a live region, which would read it twice).
    LaunchedEffect(freed) { freedText?.let(announce) }

    ToolScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        Section(R.string.settings_appearance) {
            ChoiceRow(
                label = stringResource(R.string.settings_theme),
                options = ThemeMode.entries,
                selected = state.settings.theme,
                optionLabel = { resources.getString(themeLabel(it)) },
                onSelect = onTheme,
            )
            SwitchRow(
                label = stringResource(R.string.settings_high_contrast),
                checked = state.settings.highContrast,
                stateText = stringResource(if (state.settings.highContrast) R.string.settings_on else R.string.settings_off),
                enabled = true,
                onChange = onHighContrast,
            )
            Text(stringResource(R.string.settings_high_contrast_hint), style = MaterialTheme.typography.bodySmall)
        }
        Section(R.string.settings_language) {
            if (languageSupported) {
                ChoiceRow(
                    label = stringResource(R.string.settings_language_label),
                    options = listOf("", "en", "tr"),
                    selected = language,
                    optionLabel = { resources.getString(languageLabel(it)) },
                    onSelect = onLanguage,
                )
            } else {
                Text(stringResource(R.string.settings_language_unsupported))
                OutlinedButton(
                    onClick = onOpenSystemLanguageSettings,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.settings_language_open_system)) }
            }
        }
        Section(R.string.settings_saving) {
            ChoiceRow(
                label = stringResource(R.string.settings_save_behavior),
                options = SaveBehavior.entries,
                selected = state.settings.saveBehavior,
                optionLabel = { resources.getString(saveBehaviorLabel(it)) },
                onSelect = onSaveBehavior,
            )
            Text(stringResource(R.string.settings_save_behavior_hint), style = MaterialTheme.typography.bodySmall)
        }
        Section(R.string.settings_storage) {
            Text(stringResource(R.string.settings_cache_hint))
            val working = stringResource(R.string.working)
            Button(
                onClick = onClearCache,
                enabled = !state.clearingCache,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { if (state.clearingCache) stateDescription = working },
            ) { Text(stringResource(R.string.settings_clear_cache)) }
            if (freedText != null) {
                Text(freedText, modifier = Modifier.fillMaxWidth())
            }
        }
        Section(R.string.about_title) {
            OutlinedButton(onClick = onOpenAbout, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_open_about))
            }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

private fun languageLabel(tag: String) = when (tag) {
    "en" -> R.string.settings_language_en
    "tr" -> R.string.settings_language_tr
    else -> R.string.settings_language_system
}

private fun saveBehaviorLabel(b: SaveBehavior) = when (b) {
    SaveBehavior.ASK -> R.string.settings_save_ask
    SaveBehavior.SHARE -> R.string.settings_save_share
}
