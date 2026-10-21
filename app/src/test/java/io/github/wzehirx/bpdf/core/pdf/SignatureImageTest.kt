package io.github.wzehirx.bpdf.core.pdf

import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class SignatureImageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun hasInk_isFalseForNothingOrEmptyStrokes() {
        assertFalse(SignatureImage.hasInk(emptyList()))
        assertFalse(SignatureImage.hasInk(listOf(emptyList())))
        assertTrue(SignatureImage.hasInk(listOf(listOf(SignaturePoint(0.5f, 0.5f)))))
    }

    @Test
    fun render_drawsInkOnATransparentBackground() {
        val out = File(tmp.root, "sig.png")
        SignatureImage.render(listOf(listOf(SignaturePoint(0.1f, 0.5f), SignaturePoint(0.9f, 0.5f))), out)
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertEquals(2.5f, bitmap.width.toFloat() / bitmap.height, 0.01f)
        assertEquals(0, Color.alpha(bitmap.getPixel(5, 5))) // background
        assertTrue(Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 200) // the line
        bitmap.recycle()
    }

    @Test
    fun render_singleTap_makesADot() {
        val out = File(tmp.root, "dot.png")
        SignatureImage.render(listOf(listOf(SignaturePoint(0.5f, 0.5f))), out)
        val bitmap = BitmapFactory.decodeFile(out.path)
        assertTrue(Color.alpha(bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)) > 200)
        bitmap.recycle()
    }

    @Test
    fun render_nothingDrawn_isRejected() {
        val out = File(tmp.root, "none.png")
        try {
            SignatureImage.render(listOf(emptyList()), out)
            fail()
        } catch (_: IllegalArgumentException) {
        }
        assertFalse(out.exists())
    }
}
