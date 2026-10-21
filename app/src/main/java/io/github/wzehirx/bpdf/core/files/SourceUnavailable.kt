package io.github.wzehirx.bpdf.core.files

/** The document behind a Uri could not be opened (moved, deleted, or access no longer granted). */
class SourceUnavailable(cause: Throwable) : Exception("Cannot open the document", cause)
