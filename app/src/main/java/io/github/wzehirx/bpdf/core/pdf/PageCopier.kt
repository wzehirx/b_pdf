package io.github.wzehirx.bpdf.core.pdf

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.IdentityHashMap

/** Copies pages between documents without dragging the rest of the source along. */
object PageCopier {
    /**
     * Imports [pages] (0-based indices of [source], in the given order) into [target]. Imported
     * pages share resources with [source], which therefore has to stay open until [target] is saved.
     *
     * Annotations keep references to other pages of the source (`/P`, internal link destinations),
     * and saving them would copy those pages into [target]. Links whose destination page is among
     * the copied pages are repointed to the imported page; links to any other page are removed.
     * External links and other annotations are kept. The source's annotations are not modified:
     * the imported pages get their own copies.
     */
    fun copyPages(source: PDDocument, pages: List<Int>, target: PDDocument) {
        val originals = pages.map { source.getPage(it) }
        val imported = originals.map { target.importPage(it) }
        val importedFor = IdentityHashMap<COSDictionary, COSDictionary>()
        originals.forEachIndexed { i, page -> importedFor.putIfAbsent(page.cosObject, imported[i].cosObject) }
        imported.forEach { remapAnnotations(it.cosObject, importedFor) }
    }

    private fun remapAnnotations(page: COSDictionary, importedFor: IdentityHashMap<COSDictionary, COSDictionary>) {
        val annots = page.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return
        val kept = COSArray()
        for (i in 0 until annots.size()) {
            val original = annots.getObject(i) as? COSDictionary ?: continue
            val copy = COSDictionary(original).apply { removeItem(COSName.P) }
            if (remapDestinations(copy, importedFor)) kept.add(copy)
        }
        page.setItem(COSName.ANNOTS, kept)
    }

    /** Repoints the page destinations of [annotation]; false when one points outside the copy. */
    private fun remapDestinations(
        annotation: COSDictionary,
        importedFor: IdentityHashMap<COSDictionary, COSDictionary>,
    ): Boolean {
        if (!remapDestination(annotation, COSName.DEST, importedFor)) return false
        val original = annotation.getDictionaryObject(COSName.A) as? COSDictionary ?: return true
        val action = COSDictionary(original)
        if (!remapDestination(action, COSName.D, importedFor)) return false
        annotation.setItem(COSName.A, action)
        return true
    }

    private fun remapDestination(
        holder: COSDictionary,
        key: COSName,
        importedFor: IdentityHashMap<COSDictionary, COSDictionary>,
    ): Boolean {
        val dest: COSBase? = holder.getDictionaryObject(key)
        if (dest !is COSArray || dest.size() == 0) return true
        val page = dest.getObject(0) as? COSDictionary ?: return true // remote or named destination
        val imported = importedFor[page] ?: return false
        val remapped = COSArray().apply { addAll(dest) }
        remapped.set(0, imported)
        holder.setItem(key, remapped)
        return true
    }
}
