package net.weero.measix.pilot.ui.hooks

import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatInputStateTest {
    @Test
    fun removingOneRepeatedImageLeavesTheOtherOccurrenceAndInvalidatesOnlyItsOwnEdit() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///same.png")
        state.messageContent = listOf(image, image)
        val targets = state.imageEditTargets()
        assertTrue(state.removeImage(targets[1]))
        assertEquals(listOf(image), state.messageContent)
        assertFalse(state.isImageEditCurrent(targets[1]))
        assertTrue(state.isImageEditCurrent(targets[0]))
        assertFalse(state.removeImage(targets[1]))
        assertEquals(listOf(image), state.messageContent)
    }

    @Test
    fun removingTheFirstRepeatedImageDoesNotMoveTheSecondAcrossHistoricalText() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///same.png")
        val middle = UIMessagePart.Text("between")
        val tail = UIMessagePart.Text("end")
        state.setContents(listOf(image, middle, image, tail))
        state.editingMessage = Uuid.random()
        val targets = state.imageEditTargets()
        assertTrue(state.removeImage(targets[0]))
        assertEquals(listOf(middle, image, tail), state.getContents())
        val edited = UIMessagePart.Image("file:///edited.png")
        assertTrue(state.replaceImage(targets[1], edited))
        assertEquals(listOf(middle, edited, tail), state.getContents())
    }

    @Test
    fun imageEditTargetsDistinguishRepeatedInstancesAndPreserveMessageOrder() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///same.png")
        val replacement = UIMessagePart.Image("file:///annotation.png")
        state.setContents(listOf(image, UIMessagePart.Text("between"), image, UIMessagePart.Text("end")))
        state.editingMessage = Uuid.random()
        val targets = state.imageEditTargets()
        assertTrue(state.replaceImage(targets[1], replacement))
        assertEquals(listOf(image, UIMessagePart.Text("between"), replacement, UIMessagePart.Text("end")), state.getContents())
        assertFalse(state.replaceImage(targets[1], image))
        assertTrue(state.isImageEditCurrent(targets[0]))
    }

    @Test
    fun imageEditCannotReplaceRemovedAndReselectedImageOrAnotherInput() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///same.png")
        val replacement = UIMessagePart.Image("file:///annotation.png")
        state.messageContent = listOf(image)
        val removed = state.imageEditTargets().single()
        state.messageContent = emptyList()
        state.messageContent = listOf(image)
        assertFalse(state.replaceImage(removed, replacement))
        val previousInput = state.imageEditTargets().single()
        state.setContents(listOf(image))
        assertFalse(state.replaceImage(previousInput, replacement))
        val previousEditor = state.imageEditTargets().single()
        state.editingTarget = ChatInputEditTarget.Queued(Uuid.random(), 1)
        assertFalse(state.replaceImage(previousEditor, replacement))
        assertEquals(listOf(image), state.messageContent)
    }

    @Test
    fun sendingInvalidatesImageEditBeforeCommitWithoutClearingTheDraft() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///same.png")
        state.messageContent = listOf(image)
        val target = state.imageEditTargets().single()
        val submission = state.captureSubmission()
        assertFalse(state.replaceImage(target, UIMessagePart.Image("file:///late.png")))
        assertEquals(listOf(image), submission.contents)
        assertEquals(listOf(image), state.messageContent)
        state.completeSubmission(submission)
        assertTrue(state.isEmpty())
    }

    @Test
    fun queuedEditKeepsOriginalPartOrderAndDoesNotPretendToBeHistory() {
        val state = ChatInputState()
        val image = UIMessagePart.Image("file:///queued/image.png")
        val target = ChatInputEditTarget.Queued(Uuid.random(), 4)
        state.editingTarget = target
        state.setContents(listOf(UIMessagePart.Text("first"), image, UIMessagePart.Text("last")))
        state.setMessageText("changed")
        assertTrue(state.isEditing())
        assertNull(state.editingMessage)
        assertEquals(listOf(UIMessagePart.Text("first"), image, UIMessagePart.Text("changed")), state.getContents())
        assertFalse(state.shouldDeleteFileOnRemove(image))
        val submitted = state.captureSubmission()
        state.editingTarget = target.copy(version = 5)
        state.completeSubmission(submitted)
        assertEquals("changed", state.textContent.text.toString())
        assertEquals(target.copy(version = 5), state.editingTarget)
    }

    @Test
    fun committedSubmissionClearsCapturedInputButPreservesEditsAndNewAttachments() {
        val oldAttachment = UIMessagePart.Image("file:///managed/old.png")
        val addedAttachment = UIMessagePart.Image("file:///managed/new.png")
        val state = ChatInputState()
        state.setMessageText("original")
        state.messageContent = listOf(oldAttachment)
        val submitted = state.captureSubmission()
        state.setMessageText("original with later edit")
        state.messageContent = listOf(oldAttachment, addedAttachment)
        state.completeSubmission(submitted)
        assertEquals(listOf(UIMessagePart.Text("original with later edit"), addedAttachment), state.getContents())

        val next = state.captureSubmission()
        state.completeSubmission(next)
        assertTrue(state.isEmpty())
    }

    @Test
    fun rejectedSubmissionAndLateResultAfterInputReplacementLeaveDraftIntact() {
        val state = ChatInputState()
        val attachment = UIMessagePart.Document("file:///managed/original.txt", "original.txt", "text/plain")
        state.setMessageText("unchanged after failure")
        state.messageContent = listOf(attachment)
        val rejected = state.captureSubmission()
        assertEquals(rejected.contents, state.getContents())
        state.clearInput()
        state.setMessageText("new draft")
        state.messageContent = listOf(attachment)
        state.completeSubmission(rejected)
        assertEquals(listOf(UIMessagePart.Text("new draft"), attachment), state.getContents())
    }

    @Test
    fun lateCompletionDoesNotRemoveReaddedAttachmentOrEditingInput() {
        val state = ChatInputState()
        val old = UIMessagePart.Image("file:///managed/same.png")
        state.messageContent = listOf(old)
        val submission = state.captureSubmission()
        val readded = old.copy()
        state.messageContent = listOf(readded)
        state.completeSubmission(submission)
        assertEquals(listOf(readded), state.getContents())
        val another = state.captureSubmission()
        state.editingMessage = Uuid.random()
        state.setMessageText("editing history")
        state.completeSubmission(another)
        assertEquals("editing history", state.textContent.text.toString())
        assertEquals(listOf(readded), state.messageContent)
    }

    @Test
    fun sameAttachmentInstanceRemovedAndReaddedGetsNewSelectionIdentity() {
        val state = ChatInputState()
        val part = UIMessagePart.Image("file:///managed/same.png")
        state.messageContent = listOf(part)
        val submitted = state.captureSubmission()
        state.messageContent = emptyList()
        state.messageContent = listOf(part)
        state.completeSubmission(submitted)
        assertEquals(listOf(part), state.messageContent)

        val second = state.captureSubmission()
        state.messageContent = listOf(part, part)
        state.completeSubmission(second)
        assertEquals(listOf(part), state.messageContent)
    }

    @Test
    fun attachmentOnlyInputHasNoEmptyTextAndEditPreservesOrderAndOwnership() {
        val image = UIMessagePart.Image("file:///managed/image.png")
        val state = ChatInputState()
        state.messageContent = listOf(image)
        state.setMessageText("  \n ")
        assertEquals(listOf(image), state.getContents())
        assertFalse(state.isEmpty())

        state.setContents(listOf(UIMessagePart.Text("Earlier"), image, UIMessagePart.Text("Last")))
        state.editingMessage = Uuid.random()
        state.setMessageText("")
        assertEquals(listOf(UIMessagePart.Text("Earlier"), image), state.getContents())
        assertFalse(state.shouldDeleteFileOnRemove(image))
        state.messageContent = emptyList()
        assertEquals(listOf(UIMessagePart.Text("Earlier")), state.getContents())
        assertFalse(state.isEmpty())
        state.clearInput()
        assertTrue(state.isEmpty())
    }

    @Test
    fun editingAttachmentOnlyMessageCanAddAndRemoveText() {
        val image = UIMessagePart.Image("attachment:original")
        val state = ChatInputState()
        state.setContents(listOf(image))
        state.editingMessage = Uuid.random()
        state.setMessageText("Caption")
        assertEquals(listOf(UIMessagePart.Text("Caption"), image), state.getContents())
        state.setMessageText(" ")
        assertEquals(listOf(image), state.getContents())
        state.messageContent = emptyList()
        assertTrue(state.isEmpty())
    }
}
