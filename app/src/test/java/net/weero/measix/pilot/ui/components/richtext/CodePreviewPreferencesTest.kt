package net.weero.measix.pilot.ui.components.richtext

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodePreviewPreferencesTest {
    @Test fun `closed eligible content defaults to preview and incomplete content stays source`() {
        val preferences = CodePreviewPreferences()
        assertFalse(preferences.showPreview("first", false))
        assertTrue(preferences.showPreview("first", true))
        preferences.choose("first", true)
        assertFalse(preferences.showPreview("first", false))
    }

    @Test fun `manual choice survives streaming completion and other blocks stay independent`() {
        val preferences = CodePreviewPreferences()
        preferences.observe("```html\n<p>", true)
        preferences.choose("first", false)
        preferences.observe("```html\n<p>done</p>\n```", true)
        assertFalse(preferences.showPreview("first", true))
        assertTrue(preferences.showPreview("second", true))
        preferences.observe("```html\n<p>done</p>\n```", false)
        assertFalse(preferences.showPreview("first", true))
    }

    @Test fun `replacement and edits after completion reset old choices`() {
        val preferences = CodePreviewPreferences()
        preferences.observe("original", true)
        preferences.choose("first", false)
        preferences.observe("replacement", true)
        assertTrue(preferences.showPreview("first", true))
        preferences.observe("replacement", false)
        preferences.choose("first", false)
        preferences.observe("replacement edited", false)
        assertTrue(preferences.showPreview("first", true))
    }
}
