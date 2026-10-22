package io.github.wzehirx.bpdf.core.work

import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PdfError
import io.github.wzehirx.bpdf.core.pdf.PdfLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The password question a tool is asking: for [fileName], [wrong] after a failed attempt, [checking] while testing one. */
data class PasswordPrompt(
    val fileName: String,
    val wrong: Boolean = false,
    val checking: Boolean = false,
    /** Number of wrong passwords so far; each one is announced, also when [wrong] stays true. */
    val attempt: Int = 0,
)

/** What opening a PDF showed: its page count and whether it is encrypted at all (also with an empty user password). */
data class PdfProbe(val pages: Int, val encrypted: Boolean)

/**
 * Shared password flow of every tool that opens a PDF. A ViewModel calls [open] for each imported file:
 * an unprotected file opens at once; a protected one raises [prompt] and suspends until the user
 * supplies the right password ([submit]) or gives up ([dismiss]). A wrong password keeps the prompt
 * with [PasswordPrompt.wrong] set, so the user is asked again. Correct passwords are kept per file
 * ([passwordOf]) for the operations to use.
 *
 * Passwords live only in memory here: they are never logged, never part of saved state, and wiped by
 * [clear] (call it from `onCleared`).
 */
class PasswordGate(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Opens [File] with the password (null = none) and reports pages and encryption; throws [PdfError] when it cannot. */
    private val probe: (File, String?) -> PdfProbe = { file, password ->
        PdfLoader.load(file, password).use { PdfProbe(it.numberOfPages, it.isEncrypted) }
    },
) {
    private val _prompt = MutableStateFlow<PasswordPrompt?>(null)

    /** Non-null while the user is being asked for a password. */
    val prompt: StateFlow<PasswordPrompt?> = _prompt

    private val passwords = ConcurrentHashMap<File, String>()
    private val encrypted: MutableSet<File> = ConcurrentHashMap.newKeySet()
    private val answers = Channel<String?>(Channel.UNLIMITED)

    /**
     * Opens [file] and returns its page count, asking for the password when needed. Returns null when the
     * user dismissed the prompt; the caller then discards the file. Other failures (corrupt file...) are
     * thrown. Only one file can be asked about at a time.
     */
    suspend fun open(file: ImportedFile): Int? {
        try {
            val opened = withContext(ioDispatcher) { probe(file.file, null) }
            // Opens with no password but is encrypted: owner restrictions (print, copy...) that every tool removes.
            if (opened.encrypted) encrypted += file.file
            return opened.pages
        } catch (_: PdfError.PasswordRequired) {
            // fall through to ask
        }
        while (answers.tryReceive().isSuccess) {
            // drop answers left over from an earlier prompt
        }
        _prompt.value = PasswordPrompt(file.displayName)
        var wrongAttempts = 0
        try {
            while (true) {
                val password = answers.receive() ?: return null
                try {
                    val opened = withContext(ioDispatcher) { probe(file.file, password) }
                    passwords[file.file] = password
                    encrypted += file.file
                    return opened.pages
                } catch (_: PdfError.WrongPassword) {
                    _prompt.value = PasswordPrompt(file.displayName, wrong = true, attempt = ++wrongAttempts)
                }
            }
        } finally {
            _prompt.value = null
        }
    }

    /** Offers [password] for the file being asked about; ignored while no prompt is shown or one is being checked. */
    fun submit(password: String) {
        val current = _prompt.value ?: return
        if (current.checking) return
        _prompt.value = current.copy(checking = true)
        answers.trySend(password)
    }

    /** The user gave up on the prompt; [open] returns null. */
    fun dismiss() {
        if (_prompt.value != null) answers.trySend(null)
    }

    /** The password that opened [file], or null when it needs none. */
    fun passwordOf(file: File): String? = passwords[file]

    /**
     * True when [file] is encrypted, whether it asked for a password or only carried restrictions.
     * Tools write their result without that protection and say so.
     */
    fun wasEncrypted(file: File): Boolean = file in encrypted

    /** Forgets the password of a file that was removed or replaced. */
    fun forget(file: File) {
        passwords.remove(file)
        encrypted.remove(file)
    }

    /** Forgets every password. */
    fun clear() {
        passwords.clear()
        encrypted.clear()
    }
}
