package io.github.wzehirx.bpdf.core.files

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Minimal flat-folder document provider backed by a directory, enough for DocumentFile.createFile. */
class FakeTreeProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val id = DocumentsContract.getDocumentId(uri)
        return MatrixCursor(DOC_COLUMNS).apply {
            if (id == "root") {
                addRow(arrayOf<Any>(id, "root", Document.MIME_TYPE_DIR, Document.FLAG_DIR_SUPPORTS_CREATE, 0L))
            } else {
                addRow(arrayOf<Any>(id, id, "application/pdf", Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE, 0L))
            }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "android:createDocument") return null
        val mime = extras!!.getString(Document.COLUMN_MIME_TYPE)
        val displayName = extras.getString(Document.COLUMN_DISPLAY_NAME)!!
        // Like real providers: a known mime type gets its extension appended when missing.
        val name = if (mime == "application/pdf" && !displayName.endsWith(".pdf")) "$displayName.pdf" else displayName
        File(dir, name).createNewFile()
        return Bundle().apply {
            putParcelable("uri", DocumentsContract.buildDocumentUri("test.tree", name))
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(dir, DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.parseMode(mode))

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        lateinit var dir: File
        val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
class MultiFileExportTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun registerProvider() {
        FakeTreeProvider.dir = tmp.newFolder("tree")
        val info = ProviderInfo().apply {
            authority = "test.tree"
            exported = true
        }
        Robolectric.buildContentProvider(FakeTreeProvider::class.java).create(info)
    }

    @Test
    fun exportToTree_writesEveryFileIntoFolder() {
        val a = tmp.newFile("doc_1.pdf").apply { writeText("AAA") }
        val b = tmp.newFile("doc_2.pdf").apply { writeText("BBBB") }
        val tree: Uri = DocumentsContract.buildTreeDocumentUri("test.tree", "root")
        val n = runBlocking { FileRepository(context).exportToTree(listOf(a, b), tree) }
        assertEquals(2, n)
        assertEquals(listOf("doc_1.pdf", "doc_2.pdf"), FakeTreeProvider.dir.list()!!.sorted())
        assertEquals("AAA", File(FakeTreeProvider.dir, "doc_1.pdf").readText())
        assertEquals("BBBB", File(FakeTreeProvider.dir, "doc_2.pdf").readText())
    }
}
