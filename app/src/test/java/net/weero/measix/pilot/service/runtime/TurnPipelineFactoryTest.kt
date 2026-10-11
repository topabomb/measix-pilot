package net.weero.measix.pilot.service.runtime
import net.weero.measix.pilot.service.turn.TurnPipelineFactory

import io.mockk.mockk
import net.weero.measix.pilot.data.ai.transformers.AttachmentProjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.Base64ImageToLocalFileTransformer
import net.weero.measix.pilot.data.ai.transformers.DocumentAsPromptTransformer
import net.weero.measix.pilot.data.ai.transformers.PlaceholderTransformer
import net.weero.measix.pilot.data.ai.transformers.PromptInjectionTransformer
import net.weero.measix.pilot.data.ai.transformers.RegexOutputTransformer
import net.weero.measix.pilot.data.ai.transformers.TemplateTransformer
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TimeReminderTransformer
import net.weero.measix.pilot.data.ai.transformers.ToolArtifactReplayTransformer
import net.weero.measix.pilot.data.files.ArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TurnPipelineFactory 装配等价性测试。
 * 锁定主、子助手共享的输入与输出顺序。
 */
class TurnPipelineFactoryTest {

    private fun factory(): TurnPipelineFactory = TurnPipelineFactory(
        templateTransformer = mockk<TemplateTransformer>(relaxed = true),
        toolArtifactReplayTransformer = mockk<ToolArtifactReplayTransformer>(relaxed = true),
        attachmentProjectionTransformer = AttachmentProjectionTransformer(),
        base64ImageToLocalFileTransformer = Base64ImageToLocalFileTransformer(mockk<ArtifactStore>(relaxed = true)),
        documentAsPromptTransformer = DocumentAsPromptTransformer(),
    )

    @Test
    fun `output matches documented order`() {
        assertEquals(
            listOf(
                ThinkTagTransformer::class,
                Base64ImageToLocalFileTransformer::class,
                RegexOutputTransformer::class,
            ),
            factory().output().map { it::class },
        )
    }

    @Test
    fun `shared input appends template replay then projection`() {
        val classes = factory().input().map { it::class }
        assertEquals(
            listOf(
                TimeReminderTransformer::class,
                PromptInjectionTransformer::class,
                PlaceholderTransformer::class,
                DocumentAsPromptTransformer::class,
                TemplateTransformer::class,
                ToolArtifactReplayTransformer::class,
                AttachmentProjectionTransformer::class,
            ),
            classes,
        )
    }

}
