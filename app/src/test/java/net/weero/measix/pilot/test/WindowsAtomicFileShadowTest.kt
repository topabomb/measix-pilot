package net.weero.measix.pilot.test

import android.util.AtomicFile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowsAtomicFileShadowTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `first publication replacement and failed write preserve Android atomic file semantics`() {
        val file = AtomicFile(java.io.File(temporary.root, "manifest"))
        val first = file.startWrite()
        first.write("first".toByteArray())
        file.finishWrite(first)
        assertEquals("first", file.readFully().decodeToString())
        val second = file.startWrite()
        second.write("second".toByteArray())
        file.finishWrite(second)
        assertEquals("second", file.readFully().decodeToString())
        val failed = file.startWrite()
        failed.write("uncommitted".toByteArray())
        file.failWrite(failed)
        assertEquals("second", file.readFully().decodeToString())
    }
}
