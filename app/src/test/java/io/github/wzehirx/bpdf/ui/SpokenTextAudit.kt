package io.github.wzehirx.bpdf.ui

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertTrue

/**
 * Finds text a screen reader would speak twice for one element.
 *
 * TalkBack reads a node's contentDescription, its Text and its stateDescription. A merged node (a clickable row, a
 * toggle, a card) carries the contentDescription it sets itself plus the Text of every child it swallowed, so a row that
 * sets `contentDescription = "title. description"` over visible Text "title" and "description" is spoken twice.
 *
 * [problems] returns one line per element whose spoken text repeats a phrase:
 * - a contentDescription that equals, contains or is contained in one of its Texts,
 * - a stateDescription that equals, contains or is contained in a contentDescription or a Text,
 * - two identical entries in the same list,
 * - a run of [MIN_REPEATED_WORDS] or more words that appears in two different parts (description, text, state).
 */
object SpokenText {
    private const val MIN_LENGTH = 3
    private const val MIN_REPEATED_WORDS = 3

    class Spoken(val contentDescription: List<String>, val text: List<String>, val state: String?) {
        val all: List<String> get() = contentDescription + text + listOfNotNull(state)
        val joined: String get() = all.joinToString(". ")
    }

    fun spokenOf(node: SemanticsNode): Spoken = Spoken(
        contentDescription = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty(),
        text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text },
        state = node.config.getOrNull(SemanticsProperties.StateDescription),
    )

    private fun normalize(s: String) =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun overlaps(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.length < MIN_LENGTH || y.length < MIN_LENGTH) return false
        return x == y || x in y || y in x
    }

    private fun runs(s: String): Set<String> {
        val words = normalize(s).split(' ').filter { it.isNotEmpty() }
        return (0..words.size - MIN_REPEATED_WORDS).map { words.subList(it, it + MIN_REPEATED_WORDS).joinToString(" ") }.toSet()
    }

    /** A run of [MIN_REPEATED_WORDS] words that two different [entries] share, or null (one long sentence may repeat words). */
    fun repeatedPhrase(entries: List<String>): String? {
        val sets = entries.map(::runs)
        for (i in sets.indices) for (j in i + 1 until sets.size) sets[i].firstOrNull { it in sets[j] }?.let { return it }
        return null
    }

    fun problems(spoken: Spoken): List<String> {
        val found = mutableListOf<String>()
        for (c in spoken.contentDescription) for (t in spoken.text) {
            if (overlaps(c, t)) found += "contentDescription '$c' repeats text '$t'"
        }
        spoken.state?.let { st ->
            (spoken.contentDescription + spoken.text).forEach { if (overlaps(st, it)) found += "stateDescription '$st' repeats '$it'" }
        }
        listOf(spoken.contentDescription, spoken.text).forEach { list ->
            val dup = list.map(::normalize).filter { it.length >= MIN_LENGTH }.groupBy { it }.filter { it.value.size > 1 }.keys
            dup.forEach { found += "'$it' appears twice" }
        }
        repeatedPhrase(spoken.all)?.let { found += "phrase '$it' is spoken in two parts of '${spoken.joined}'" }
        return found.distinct()
    }

    /** Walks the merged semantics tree under [root] and returns "screen: node: problem" lines. */
    fun audit(root: SemanticsNode, screen: String): List<String> {
        val out = mutableListOf<String>()
        fun visit(node: SemanticsNode) {
            val spoken = spokenOf(node)
            problems(spoken).forEach { out += "$screen: '${spoken.joined}' -> $it" }
            node.children.forEach(::visit)
        }
        visit(root)
        return out
    }
}

/** Fails when any element of the merged semantics tree speaks the same phrase twice (see [SpokenText]). */
fun ComposeTestRule.assertNoDoubleSpeech(screen: String) {
    waitForIdle()
    val problems = SpokenText.audit(onRoot().fetchSemanticsNode(), screen)
    assertTrue("Double speech:\n" + problems.joinToString("\n"), problems.isEmpty())
}
