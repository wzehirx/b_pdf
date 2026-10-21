package io.github.wzehirx.bpdf.core.files

private val INVALID = Regex("""[/\\:*?"<>|\p{Cntrl}]""")

/** The display name without its extension, with invalid file-name characters replaced; `bpdf` if empty. */
fun baseNameOf(sourceName: String?): String {
    val raw = sourceName?.trim().orEmpty()
    val dot = raw.lastIndexOf('.')
    val base = (if (dot >= 0) raw.substring(0, dot) else raw).replace(INVALID, "_").trim()
    return base.ifEmpty { "bpdf" }
}

/**
 * Builds an output file name like `report_merged.pdf` from the source's display name:
 * drops its extension, replaces characters invalid in file names with `_`, and falls back to
 * `bpdf` when there is no usable name.
 */
fun suggestName(sourceName: String?, suffix: String, ext: String): String =
    "${baseNameOf(sourceName)}_$suffix.$ext"
