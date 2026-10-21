package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** Removes pages from a PDF, or extracts selected pages into a new one. */
object PageSelectOperation {
    /**
     * Writes [input] without the 0-based [pages] to [out] and returns the page count of the result.
     * The pages are removed from the loaded document itself, so document info, metadata, outline,
     * language and tagging are preserved; links, outline entries and structure elements that
     * pointed at a removed page lose that target (and with it the page's content).
     * Throws [IllegalArgumentException] when [pages] is empty, contains an index outside the
     * document, or covers every page (a PDF needs at least one page).
     */
    fun remove(
        input: File,
        pages: List<Int>,
        out: File,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int = run(input, out, password, progress, isCancelled) { source ->
        val total = source.numberOfPages
        requireValid(pages, total)
        val removed = pages.toSortedSet()
        require(removed.size < total) { "Cannot remove every page" }
        val removedPages = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        removed.forEach { removedPages += source.getPage(it).cosObject }
        removed.reversed().forEach { source.removePage(it) }
        DetachedPageReferences.scrub(source, removedPages)
        progress(0.6f)
        source
    }

    /**
     * Writes only the 0-based [pages] of [input], in the given order, to [out] and returns the
     * page count of the result. Document info and language are carried over. Throws
     * [IllegalArgumentException] when [pages] is empty or contains an index outside the document.
     */
    fun extract(
        input: File,
        pages: List<Int>,
        out: File,
        password: String? = null,
        progress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): Int = run(input, out, password, progress, isCancelled) { source ->
        requireValid(pages, source.numberOfPages)
        // Imported pages share resources with [source], which therefore stays open until saved.
        val result = PDDocument(MemoryUsageSetting.setupTempFileOnly())
        try {
            PageCopier.copyPages(source, pages, result)
            result.documentInformation = PDDocumentInformation(COSDictionary(source.documentInformation.cosObject))
            result.documentCatalog.language = source.documentCatalog.language
        } catch (t: Throwable) {
            runCatching { result.close() }
            throw t
        }
        progress(0.6f)
        result
    }

    private fun requireValid(pages: List<Int>, total: Int) {
        require(pages.isNotEmpty()) { "No pages selected" }
        require(pages.all { it in 0 until total }) { "Page index outside 0..${total - 1}" }
    }

    /**
     * Loads [input], lets [build] turn it into the document to save (the loaded document itself or
     * a new one) and saves that to [out]. [isCancelled] is polled before loading, before saving
     * and after saving; any failure or cancellation leaves no [out].
     */
    private fun run(
        input: File,
        out: File,
        password: String?,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        build: (source: PDDocument) -> PDDocument,
    ): Int {
        var success = false
        try {
            if (isCancelled()) throw CancellationException("Cancelled")
            PdfLoader.load(input, password).use { source ->
                progress(0.1f)
                val result = build(source)
                try {
                    if (isCancelled()) throw CancellationException("Cancelled")
                    result.isAllSecurityToBeRemoved = true
                    try {
                        result.save(out)
                    } catch (e: IOException) {
                        throw PdfError.Io(e)
                    }
                    val count = result.numberOfPages
                    if (isCancelled()) throw CancellationException("Cancelled")
                    progress(1f)
                    success = true
                    return count
                } finally {
                    if (result !== source) runCatching { result.close() }
                }
            }
        } finally {
            if (!success) out.delete()
        }
    }
}

/**
 * Cleans up references to pages that were removed from a loaded document. Saving would otherwise
 * write those pages (and their content) again, because links, outline entries and structure
 * elements still point at them.
 */
private object DetachedPageReferences {
    fun scrub(doc: PDDocument, removed: Set<COSDictionary>) {
        fun targetsRemoved(base: COSBase?): Boolean =
            base is COSArray && base.size() > 0 && base.getObject(0) in removed

        fun COSDictionary.targetsRemovedPage(): Boolean {
            if (targetsRemoved(getDictionaryObject(COSName.DEST))) return true
            val action = getDictionaryObject(COSName.A) as? COSDictionary ?: return false
            return targetsRemoved(action.getDictionaryObject(COSName.D))
        }

        for (page in doc.pages) {
            val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            val kept = COSArray()
            for (i in 0 until annots.size()) {
                val annotation = annots.getObject(i) as? COSDictionary ?: continue
                if (!annotation.targetsRemovedPage()) kept.add(annots.get(i))
            }
            if (kept.size() != annots.size()) page.cosObject.setItem(COSName.ANNOTS, kept)
        }

        val catalog = doc.documentCatalog.cosObject
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())

        fun scrubOutline(first: COSDictionary?) {
            var item = first
            while (item != null && visited.add(item)) {
                if (item.targetsRemovedPage()) {
                    item.removeItem(COSName.DEST)
                    item.removeItem(COSName.A)
                }
                scrubOutline(item.getDictionaryObject(COSName.FIRST) as? COSDictionary)
                item = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
            }
        }
        scrubOutline((catalog.getDictionaryObject(COSName.OUTLINES) as? COSDictionary)
            ?.getDictionaryObject(COSName.FIRST) as? COSDictionary)

        fun scrubStructure(node: COSBase?) {
            when (node) {
                is COSArray -> for (i in 0 until node.size()) scrubStructure(node.getObject(i))
                is COSDictionary -> {
                    if (!visited.add(node)) return
                    if (node.getDictionaryObject(COSName.PG) in removed) node.removeItem(COSName.PG)
                    scrubStructure(node.getDictionaryObject(COSName.K))
                }
            }
        }
        scrubStructure(catalog.getDictionaryObject(COSName.STRUCT_TREE_ROOT))
    }
}
