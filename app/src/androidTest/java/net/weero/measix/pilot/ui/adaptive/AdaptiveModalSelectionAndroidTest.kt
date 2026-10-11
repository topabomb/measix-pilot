package net.weero.measix.pilot.ui.adaptive

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveModalSelectionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactNestedSelectionCopiesInItsOwnWindow() = verifySelection(360)
    @Test fun expandedNestedSelectionCopiesInItsOwnWindow() = verifySelection(1000)

    private fun verifySelection(width: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        try {
            compose.setContent {
                MaterialTheme {
                    val layout = rememberAdaptiveLayoutInfo().copy(windowSize = DpSize(width.dp, 800.dp),
                        separatingVerticalHingeBounds = emptyList(), separatingHorizontalHingeBounds = emptyList())
                    CompositionLocalProvider(LocalAdaptiveLayoutInfo provides layout) {
                        var child by remember { mutableStateOf(false) }
                        AdaptiveModal(onDismissRequest = {}) {
                            SelectionContainer {
                                Column {
                                    Text("OuterSelection", Modifier.padding(24.dp))
                                    TextButton(onClick = { child = true }) { Text("Open child") }
                                    if (child) AdaptiveModal(onDismissRequest = { child = false }) {
                                        SelectionContainer { Text("InnerSelection", Modifier.padding(24.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            fun copyBySystemTouch(text: String) {
                compose.runOnIdle { clipboard.clearPrimaryClip() }
                automation.waitForIdle(200, 5_000)
                compose.onNodeWithText(text).performTouchInput { longClick() }
                val copy = compose.activity.getString(android.R.string.copy)
                val bounds = Rect()
                // Text queries do not enter the remote toolbar's embedded SurfaceView tree.
                fun descendants(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
                    yield(node)
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let { yieldAll(descendants(it)) }
                    }
                }
                compose.waitUntil(5_000) {
                    automation.windows.asSequence().mapNotNull { it.root }
                        .flatMap { descendants(it) }
                        .firstOrNull {
                            (it.text?.toString() == copy || it.contentDescription?.toString() == copy) &&
                                it.isVisibleToUser
                        }?.let { it.getBoundsInScreen(bounds); !bounds.isEmpty } == true
                }
                // Inject through the system, so a menu behind another modal cannot falsely pass.
                val down = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                    try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                }
                compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == text }
            }
            compose.onNodeWithText("Open child").performClick()
            copyBySystemTouch("InnerSelection")
            pressBack()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("InnerSelection").fetchSemanticsNodes().isEmpty()
            }
            // Compose removal does not wait for the native modal and accessibility windows.
            automation.waitForIdle(200, 5_000)
            copyBySystemTouch("OuterSelection")
        } finally {
            clipboard.clearPrimaryClip()
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }
}
