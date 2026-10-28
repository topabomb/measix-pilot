package com.termux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale

class TerminalProtocolInputTest {
    @Test
    fun `invalid termcap hex cannot abort terminal output or poison subsequent input`() {
        for (encoded in listOf("zz", "6g", "6")) {
            val (terminal, output) = fixture()
            terminal.feed("\u001bP+q$encoded;436f\u001b\\OK")
            assertEquals("OK", terminal.screen.getSelectedText(0, 0, 79, 0))
            assertEquals("\u001bP1+r436f=323536\u001b\\", output.toString("UTF-8"))
        }
    }

    @Test
    fun `invalid color reset indexes cannot abort output and valid later indexes still reset`() {
        for (invalid in listOf("bad", "-1", "999999999999999", "999")) {
            val terminal = fixture().first
            terminal.feed("\u001b]4;1;#123456\u0007")
            terminal.feed("\u001b]104;$invalid;1\u0007OK")
            assertEquals("OK", terminal.screen.getSelectedText(0, 0, 79, 0))
            assertEquals(TerminalColors.COLOR_SCHEME.mDefaultColors[1], terminal.mColors.mCurrentColors[1])
            terminal.feed("\u001b]104;$invalid\u0007NEXT")
            assertEquals("OKNEXT", terminal.screen.getSelectedText(0, 0, 79, 0))
        }
    }

    @Test
    fun `SGR mouse report uses ASCII protocol digits in every device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val (terminal, output) = fixture()
            terminal.feed("\u001b[?1006h")
            terminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 12, 3, true)
            terminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 12, 3, false)
            assertEquals("\u001b[<0;12;3M\u001b[<0;12;3m", output.toString("UTF-8"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun TerminalEmulator.feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        append(bytes, bytes.size)
    }

    private fun fixture(): Pair<TerminalEmulator, ByteArrayOutputStream> {
        val bytes = ByteArrayOutputStream()
        val output = object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) { bytes.write(data, offset, count) }
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }
        val client = object : TerminalSessionClient {
            override fun onTextChanged(session: TerminalSession?) = Unit
            override fun onTitleChanged(session: TerminalSession?) = Unit
            override fun onSessionFinished(session: TerminalSession?) = Unit
            override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) = Unit
            override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
            override fun onBell(session: TerminalSession?) = Unit
            override fun onColorsChanged(session: TerminalSession?) = Unit
            override fun onTerminalCursorStateChange(state: Boolean) = Unit
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) = Unit
            override fun logWarn(tag: String?, message: String?) = Unit
            override fun logInfo(tag: String?, message: String?) = Unit
            override fun logDebug(tag: String?, message: String?) = Unit
            override fun logVerbose(tag: String?, message: String?) = Unit
            override fun logStackTraceWithMessage(tag: String?, message: String?, error: Exception?) = Unit
            override fun logStackTrace(tag: String?, error: Exception?) = Unit
        }
        return TerminalEmulator(output, 80, 24, null, client) to bytes
    }
}
