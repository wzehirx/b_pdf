package io.github.wzehirx.bpdf.core.work

import io.github.wzehirx.bpdf.core.files.ImportedFile
import io.github.wzehirx.bpdf.core.pdf.PdfError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PasswordGateTest {
    private val locked = ImportedFile(File("locked.pdf"), "locked.pdf", 1)
    private val open = ImportedFile(File("open.pdf"), "open.pdf", 1)

    private fun gate(dispatcher: kotlinx.coroutines.CoroutineDispatcher) = PasswordGate(dispatcher) { file, password ->
        when {
            file.name != "locked.pdf" -> 4
            password == null -> throw PdfError.PasswordRequired()
            password == "right" -> 7
            else -> throw PdfError.WrongPassword()
        }
    }

    @Test
    fun unprotectedFile_opensWithoutPrompt() = runTest(UnconfinedTestDispatcher()) {
        val gate = gate(UnconfinedTestDispatcher(testScheduler))
        assertEquals(4, gate.open(open))
        assertNull(gate.prompt.value)
        assertNull(gate.passwordOf(open.file))
    }

    @Test
    fun protectedFile_promptsAndOpensWithRightPassword() = runTest(UnconfinedTestDispatcher()) {
        val gate = gate(UnconfinedTestDispatcher(testScheduler))
        val result = async { gate.open(locked) }
        assertEquals(PasswordPrompt("locked.pdf"), gate.prompt.value)
        gate.submit("right")
        assertEquals(7, result.await())
        assertNull(gate.prompt.value)
        assertEquals("right", gate.passwordOf(locked.file))
    }

    @Test
    fun wrongPassword_promptsAgainWithWrongFlag() = runTest(UnconfinedTestDispatcher()) {
        val gate = gate(UnconfinedTestDispatcher(testScheduler))
        val result = async { gate.open(locked) }
        gate.submit("bad")
        assertEquals(PasswordPrompt("locked.pdf", wrong = true, attempt = 1), gate.prompt.value)
        assertNull(gate.passwordOf(locked.file))
        gate.submit("right")
        assertEquals(7, result.await())
    }

    @Test
    fun dismiss_returnsNullAndClearsPrompt() = runTest(UnconfinedTestDispatcher()) {
        val gate = gate(UnconfinedTestDispatcher(testScheduler))
        val result = async { gate.open(locked) }
        assertTrue(gate.prompt.value != null)
        gate.dismiss()
        assertNull(result.await())
        assertNull(gate.prompt.value)
        assertNull(gate.passwordOf(locked.file))
    }

    @Test
    fun clear_forgetsPasswords() = runTest(UnconfinedTestDispatcher()) {
        val gate = gate(UnconfinedTestDispatcher(testScheduler))
        val result = async { gate.open(locked) }
        gate.submit("right")
        result.await()
        gate.clear()
        assertNull(gate.passwordOf(locked.file))
    }

    @Test
    fun submitWithoutPrompt_isIgnored() = runTest(UnconfinedTestDispatcher()) {
        val gate = gate(UnconfinedTestDispatcher(testScheduler))
        gate.submit("right")
        val result = async { gate.open(locked) }
        // a stale answer must not auto-unlock the next prompt
        assertFalse(result.isCompleted)
        gate.dismiss()
        assertNull(result.await())
    }

    @Test
    fun eachWrongPassword_raisesTheAttemptCounter() = runTest(UnconfinedTestDispatcher()) {
        val gate = gate(UnconfinedTestDispatcher(testScheduler))
        val result = async { gate.open(locked) }
        gate.submit("bad")
        assertEquals(1, gate.prompt.value!!.attempt)
        gate.submit("bad2")
        assertEquals(2, gate.prompt.value!!.attempt)
        assertTrue(gate.prompt.value!!.wrong)
        gate.dismiss()
        result.await()
    }
}
