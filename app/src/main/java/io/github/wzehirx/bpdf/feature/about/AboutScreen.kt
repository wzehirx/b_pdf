package io.github.wzehirx.bpdf.feature.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.wzehirx.bpdf.BuildConfig
import io.github.wzehirx.bpdf.R
import io.github.wzehirx.bpdf.core.a11y.rememberAnnouncer
import io.github.wzehirx.bpdf.ui.components.SecondaryButton
import io.github.wzehirx.bpdf.ui.components.ToolSection
import io.github.wzehirx.bpdf.ui.components.ToolScaffold
import io.github.wzehirx.bpdf.ui.components.openUrl

const val SOURCE_URL = "https://github.com/wzehirx/b_pdf"
const val LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val announce = rememberAnnouncer()
    val licenses = remember {
        resources.openRawResource(R.raw.licenses).bufferedReader().use { parseLicenses(it.readText()) }
    }
    val noBrowser = stringResource(R.string.about_no_browser)
    AboutScreenContent(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        licenses = licenses,
        onBack = onBack,
        onOpenUrl = { if (!context.openUrl(it)) announce(noBrowser) },
    )
}

@Composable
fun AboutScreenContent(
    versionName: String,
    versionCode: Int,
    licenses: List<LicenseEntry>,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    ToolScaffold(title = stringResource(R.string.about_title), onBack = onBack) {
        ToolSection {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, versionName, versionCode), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.about_tagline))
            Text(stringResource(R.string.about_privacy))
        }
        ToolSection(title = stringResource(R.string.about_license_heading)) {
            Text(stringResource(R.string.about_license_text))
            SecondaryButton(text = stringResource(R.string.about_license_open), onClick = { onOpenUrl(LICENSE_URL) })
        }
        ToolSection(title = stringResource(R.string.about_source_heading)) {
            SecondaryButton(text = stringResource(R.string.about_source_open), onClick = { onOpenUrl(SOURCE_URL) })
        }
        ToolSection(title = stringResource(R.string.about_third_party_heading)) {
            licenses.forEach { entry -> LicenseRow(entry, onOpen = { onOpenUrl(entry.url) }) }
        }
    }
}

/** One component: reads "name, license", opens its page. */
@Composable
private fun LicenseRow(entry: LicenseEntry, onOpen: () -> Unit) {
    val openLabel = stringResource(R.string.about_open_project_page)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            Text(entry.license, style = MaterialTheme.typography.bodyMedium)
            Text(entry.url, style = MaterialTheme.typography.bodySmall)
        }
    }
}
