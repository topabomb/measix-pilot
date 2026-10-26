package net.weero.measix.pilot.ui.pages.webdav

import org.junit.Test

class WebDavFileVMTest {
    @Test
    fun browserViewModelCanInitialize() {
        // Resolve generated static initializers too; a class literal alone misses linkage failures.
        val type = WebDavFileVM::class.java
        Class.forName(type.name, true, type.classLoader)
    }
}
