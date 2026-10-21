package io.github.wzehirx.bpdf.feature.about

import org.json.JSONArray

/** One third-party component shown in the About screen. */
data class LicenseEntry(val name: String, val license: String, val url: String)

/** Parses `res/raw/licenses.json`: an array of objects with `name`, `license` and `url`. Entries missing a name are skipped. */
fun parseLicenses(json: String): List<LicenseEntry> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        LicenseEntry(name, o.optString("license"), o.optString("url"))
    }
}
