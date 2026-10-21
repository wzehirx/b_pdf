package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** Removes pages from a PDF, or extracts selected pages into a new one. */
object PageSelectOperation {
    /** How often [remove] had to rebuild its output because the in-place result still held a removed page. */
    internal val fallbackRebuilds = java.util.concurrent.atomic.AtomicInteger()

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
    ): Int = run(
        input, out, password, progress, isCancelled,
        afterSave = { saved -> ensureNoRemovedPageSurvived(input, password, pages.toSet(), saved) },
    ) { source ->
        val total = source.numberOfPages
        requireValid(pages, total)
        val removed = pages.toSortedSet()
        require(removed.size < total) { "Cannot remove every page" }
        val removedPages = identitySet<COSDictionary>()
        val removedAnnotations = identitySet<COSDictionary>()
        removed.forEach { index ->
            val page = source.getPage(index).cosObject
            removedPages += page
            val annots = page.getDictionaryObject(COSName.ANNOTS) as? COSArray
            for (i in 0 until (annots?.size() ?: 0)) (annots!!.getObject(i) as? COSDictionary)?.let { removedAnnotations += it }
        }
        removed.reversed().forEach { source.removePage(it) }
        DetachedPageReferences(source, removedPages, removedAnnotations).scrub()
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
            copyDocumentProperties(source, result)
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
     * a new one) and saves that to [out], then runs [afterSave] on the written file. [isCancelled] is
     * polled before loading, before saving and after saving; any failure or cancellation leaves no [out].
     */
    private fun run(
        input: File,
        out: File,
        password: String?,
        progress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        afterSave: (saved: File) -> Unit = {},
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
                    afterSave(out)
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

    private fun <T> identitySet(): MutableSet<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())

    /** Document info, XMP metadata and language: what identifies the document rather than its pages. */
    private fun copyDocumentProperties(source: PDDocument, target: PDDocument) {
        target.documentInformation = PDDocumentInformation(COSDictionary(source.documentInformation.cosObject))
        target.documentCatalog.language = source.documentCatalog.language
        source.documentCatalog.metadata?.let { metadata ->
            val xmp = PDMetadata(target)
            xmp.importXMPMetadata(metadata.createInputStream().use { it.readBytes() })
            target.documentCatalog.metadata = xmp
        }
    }

    /** True when [file] holds a `/Type /Page` object that is not part of its page tree. */
    private fun hasOrphanPages(file: File): Boolean = PdfLoader.load(file).use { doc ->
        val inTree = identitySet<COSDictionary>()
        for (i in 0 until doc.numberOfPages) inTree += doc.getPage(i).cosObject
        doc.document.getObjectsByType(COSName.PAGE).any { (it.getObject() as? COSDictionary) !in inTree }
    }

    /**
     * Safety net for [remove]: the scrub knows the usual ways a page is referenced, but a PDF can
     * reference it from anywhere. If the saved file still carries a page that is not in its page
     * tree, that page's content is in the file, so the output is rebuilt from the kept pages only.
     */
    private fun ensureNoRemovedPageSurvived(input: File, password: String?, removed: Set<Int>, saved: File) {
        if (!hasOrphanPages(saved)) return
        fallbackRebuilds.incrementAndGet()
        saved.delete()
        PdfLoader.load(input, password).use { source ->
            val kept = (0 until source.numberOfPages).filterNot { it in removed }
            val result = PDDocument(MemoryUsageSetting.setupTempFileOnly())
            try {
                try {
                    PageCopier.copyPages(source, kept, result)
                    copyDocumentProperties(source, result)
                    copyOutline(source, kept, result)
                    result.isAllSecurityToBeRemoved = true
                    result.save(saved)
                } catch (e: IOException) {
                    throw PdfError.Io(e)
                }
            } finally {
                runCatching { result.close() }
            }
        }
        if (hasOrphanPages(saved)) throw PdfError.Io(IOException("Removed pages could not be separated from the output"))
    }

    /** Copies outline entries; those targeting a kept page point at its imported twin, the rest keep only their title. */
    private fun copyOutline(source: PDDocument, kept: List<Int>, target: PDDocument) {
        val sourceOutline = source.documentCatalog.documentOutline ?: return
        val importedFor = IdentityHashMap<COSDictionary, COSDictionary>()
        kept.forEachIndexed { i, index -> importedFor[source.getPage(index).cosObject] = target.getPage(i).cosObject }

        fun destinationOf(item: PDOutlineItem): COSArray? {
            val direct = item.cosObject.getDictionaryObject(COSName.DEST)
            val array = direct as? COSArray
                ?: (item.cosObject.getDictionaryObject(COSName.A) as? COSDictionary)?.getDictionaryObject(COSName.D) as? COSArray
            return array
        }

        fun copyChildren(from: PDOutlineNode, to: PDOutlineNode, depth: Int) {
            if (depth > 64) return
            for (child in from.children()) {
                val item = PDOutlineItem()
                item.title = child.title
                listOf(COSName.C, COSName.F).forEach { key -> child.cosObject.getItem(key)?.let { item.cosObject.setItem(key, it) } }
                destinationOf(child)?.let { array ->
                    val page = array.getObject(0) as? COSDictionary
                    val imported = importedFor[page]
                    if (imported != null) {
                        item.cosObject.setItem(COSName.DEST, COSArray().apply { addAll(array); set(0, imported) })
                    }
                }
                to.addLast(item)
                copyChildren(child, item, depth + 1)
                if (child.isNodeOpen) item.openNode()
            }
        }

        val outline = PDDocumentOutline()
        copyChildren(sourceOutline, outline, 0)
        outline.openNode()
        target.documentCatalog.documentOutline = outline
    }
}

/**
 * Cleans up references to pages that were removed from a loaded document. Saving would otherwise
 * write those pages (and their content) again, because links, named destinations, form fields,
 * actions, article threads, outline entries and structure elements still point at them.
 * [removed] holds the removed page dictionaries and [removedAnnotations] the annotations that
 * were on them (all compared by identity).
 */
private class DetachedPageReferences(
    private val doc: PDDocument,
    private val removed: Set<COSDictionary>,
    private val removedAnnotations: Set<COSDictionary>,
) {
    private val catalog = doc.documentCatalog.cosObject
    private val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())

    private fun name(key: String) = COSName.getPDFName(key)

    fun scrub() {
        scrubPages()
        scrubNamedDestinations()
        scrubCatalogActions()
        scrubForm()
        scrubThreads()
        scrubOutline((catalog.getDictionaryObject(COSName.OUTLINES) as? COSDictionary)
            ?.getDictionaryObject(COSName.FIRST) as? COSDictionary)
        scrubStructure(catalog.getDictionaryObject(COSName.STRUCT_TREE_ROOT))
    }

    private fun arrayTargetsRemoved(base: COSBase?): Boolean =
        base is COSArray && base.size() > 0 && base.getObject(0) in removed

    private fun destinationTargetsRemoved(base: COSBase?): Boolean =
        arrayTargetsRemoved(base) || (base is COSDictionary && arrayTargetsRemoved(base.getDictionaryObject(COSName.D)))

    private fun actionTargetsRemoved(action: COSBase?, depth: Int = 0): Boolean {
        val dict = action as? COSDictionary ?: return false
        if (arrayTargetsRemoved(dict.getDictionaryObject(COSName.D))) return true
        if (depth > 32) return false
        return when (val next = dict.getDictionaryObject(COSName.NEXT)) {
            is COSDictionary -> actionTargetsRemoved(next, depth + 1)
            is COSArray -> (0 until next.size()).any { actionTargetsRemoved(next.getObject(it), depth + 1) }
            else -> false
        }
    }

    private fun COSDictionary.additionalActionsTargetRemoved(): Boolean {
        val aa = getDictionaryObject(name("AA")) as? COSDictionary ?: return false
        return aa.keySet().any { actionTargetsRemoved(aa.getDictionaryObject(it)) }
    }

    /** Removes the entries of an additional-actions dictionary that jump to a removed page. */
    private fun COSDictionary.dropAdditionalActionsToRemoved() {
        val aa = getDictionaryObject(name("AA")) as? COSDictionary ?: return
        aa.keySet().toList().filter { actionTargetsRemoved(aa.getDictionaryObject(it)) }.forEach { aa.removeItem(it) }
    }

    private fun COSDictionary.linksToRemovedPage(): Boolean =
        destinationTargetsRemoved(getDictionaryObject(COSName.DEST)) ||
            actionTargetsRemoved(getDictionaryObject(COSName.A)) || additionalActionsTargetRemoved()

    private fun scrubPages() {
        for (page in doc.pages) {
            page.cosObject.dropAdditionalActionsToRemoved()
            val annots = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            val all = (0 until annots.size()).mapNotNull { annots.getObject(it) as? COSDictionary }
            val dropped = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
            all.filterTo(dropped) { it.linksToRemovedPage() }
            // Popups and replies hang off the annotation they belong to, which would pull it back in.
            all.filterTo(dropped) {
                it.getDictionaryObject(COSName.PARENT) in dropped || it.getDictionaryObject(name("IRT")) in dropped
            }
            if (dropped.isEmpty()) continue
            val kept = COSArray()
            for (i in 0 until annots.size()) {
                val annotation = annots.getObject(i) as? COSDictionary ?: continue
                if (annotation !in dropped) kept.add(annots.get(i))
            }
            page.cosObject.setItem(COSName.ANNOTS, kept)
        }
    }

    private fun scrubNamedDestinations() {
        (catalog.getDictionaryObject(COSName.DESTS) as? COSDictionary)?.let { dests ->
            dests.keySet().toList()
                .filter { destinationTargetsRemoved(dests.getDictionaryObject(it)) }
                .forEach { dests.removeItem(it) }
        }
        val tree = (catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary)
            ?.getDictionaryObject(COSName.DESTS) as? COSDictionary
        if (tree != null) scrubNameTree(tree, 0)
    }

    private fun scrubNameTree(node: COSDictionary, depth: Int) {
        if (depth > 64 || !visited.add(node)) return
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { names ->
            val kept = COSArray()
            var i = 0
            while (i + 1 < names.size()) {
                if (!destinationTargetsRemoved(names.getObject(i + 1))) {
                    kept.add(names.get(i))
                    kept.add(names.get(i + 1))
                }
                i += 2
            }
            if (kept.size() != names.size()) node.setItem(COSName.NAMES, kept)
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids ->
            for (i in 0 until kids.size()) (kids.getObject(i) as? COSDictionary)?.let { scrubNameTree(it, depth + 1) }
        }
    }

    private fun scrubCatalogActions() {
        val open = catalog.getDictionaryObject(name("OpenAction"))
        if (destinationTargetsRemoved(open) || actionTargetsRemoved(open)) catalog.removeItem(name("OpenAction"))
        catalog.dropAdditionalActionsToRemoved()
    }

    /** Drops form fields (and whole subtrees) whose widgets all sat on removed pages. */
    private fun scrubForm() {
        val form = catalog.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary ?: return
        val fields = form.getDictionaryObject(COSName.FIELDS) as? COSArray ?: return
        val dropped = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())

        fun isDropped(field: COSDictionary, depth: Int): Boolean {
            if (depth > 64) return false
            val onRemovedPage = field in removedAnnotations || field.getDictionaryObject(COSName.P) in removed
            val kids = field.getDictionaryObject(COSName.KIDS) as? COSArray ?: return onRemovedPage
            val kept = COSArray()
            for (i in 0 until kids.size()) {
                val kid = kids.getObject(i) as? COSDictionary ?: continue
                if (isDropped(kid, depth + 1)) dropped += kid else kept.add(kids.get(i))
            }
            if (kept.size() != kids.size()) field.setItem(COSName.KIDS, kept)
            return kept.size() == 0
        }

        val keptFields = COSArray()
        for (i in 0 until fields.size()) {
            val field = fields.getObject(i) as? COSDictionary ?: continue
            if (isDropped(field, 0)) dropped += field else keptFields.add(fields.get(i))
        }
        form.setItem(COSName.FIELDS, keptFields)
        (form.getDictionaryObject(name("CO")) as? COSArray)?.let { order ->
            val keptOrder = COSArray()
            for (i in 0 until order.size()) if (order.getObject(i) !in dropped) keptOrder.add(order.get(i))
            form.setItem(name("CO"), keptOrder)
        }
    }

    /** Takes beads on removed pages out of their article threads (and whole threads left empty). */
    private fun scrubThreads() {
        val threads = catalog.getDictionaryObject(name("Threads")) as? COSArray ?: return
        val keptThreads = COSArray()
        for (t in 0 until threads.size()) {
            val thread = threads.getObject(t) as? COSDictionary ?: continue
            val first = thread.getDictionaryObject(COSName.F) as? COSDictionary
            val beads = ArrayList<COSDictionary>()
            var bead = first
            while (bead != null && beads.none { it === bead } && beads.size < 100_000) {
                beads += bead
                bead = bead.getDictionaryObject(name("N")) as? COSDictionary
            }
            val kept = beads.filter { it.getDictionaryObject(name("P")) !in removed }
            if (kept.size == beads.size) {
                keptThreads.add(threads.get(t))
                continue
            }
            if (kept.isEmpty()) continue
            kept.forEachIndexed { i, b ->
                b.setItem(name("N"), kept[(i + 1) % kept.size])
                b.setItem(name("V"), kept[(i + kept.size - 1) % kept.size])
            }
            thread.setItem(COSName.F, kept[0])
            keptThreads.add(threads.get(t))
        }
        catalog.setItem(name("Threads"), keptThreads)
    }

    private fun scrubOutline(first: COSDictionary?) {
        var item = first
        while (item != null && visited.add(item)) {
            if (item.linksToRemovedPage()) {
                item.removeItem(COSName.DEST)
                item.removeItem(COSName.A)
            }
            scrubOutline(item.getDictionaryObject(COSName.FIRST) as? COSDictionary)
            item = item.getDictionaryObject(COSName.NEXT) as? COSDictionary
        }
    }

    private fun COSBase.isReferenceToRemovedContent(): Boolean {
        val dict = this as? COSDictionary ?: return false
        val type = dict.getNameAsString(COSName.TYPE)
        return (type == "OBJR" && dict.getDictionaryObject(name("Obj")) in removedAnnotations) ||
            ((type == "OBJR" || type == "MCR") && dict.getDictionaryObject(COSName.PG) in removed)
    }

    private fun scrubStructure(node: COSBase?) {
        when (node) {
            is COSArray -> {
                var i = 0
                while (i < node.size()) {
                    if (node.getObject(i)?.isReferenceToRemovedContent() == true) node.remove(i) else i++
                }
                for (j in 0 until node.size()) scrubStructure(node.getObject(j))
            }
            is COSDictionary -> {
                if (!visited.add(node)) return
                if (node.getDictionaryObject(COSName.PG) in removed) node.removeItem(COSName.PG)
                val kids = node.getDictionaryObject(COSName.K)
                if (kids?.isReferenceToRemovedContent() == true) node.removeItem(COSName.K) else scrubStructure(kids)
            }
        }
    }
}
