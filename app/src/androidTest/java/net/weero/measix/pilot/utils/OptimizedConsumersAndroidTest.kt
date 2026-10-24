package net.weero.measix.pilot.utils

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.search.SearchProviderType
import me.rerere.search.SearchService
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.UserConfiguration
import net.weero.measix.pilot.data.files.SkillFrontmatterParser
import net.weero.measix.pilot.data.files.SkillParseResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Run against the optimized target APK as well as Debug; no test-only parser or factory. */
@RunWith(AndroidJUnit4::class)
class OptimizedConsumersAndroidTest {
    @Test
    fun skillYamlConsumesScalarsNestedCollectionsAndRejectsInvalidDocuments() {
        val normal = SkillFrontmatterParser.parseDocument(
            "---\nname: Example\ndescription: Useful skill\ncompatibility: Android\n---\nBody"
        ) as SkillParseResult.Success
        assertEquals("Example", normal.document.frontmatter.name)
        assertEquals("Useful skill", normal.document.frontmatter.description)
        assertEquals("Android", normal.document.frontmatter.compatibility)
        assertEquals("Body", normal.document.body)

        val nested = SkillFrontmatterParser.parseDocument(
            "---\nname: Nested\ndescription: |\n  First line\n  Second line\n" +
                "metadata:\n  items:\n    - enabled: true\n      count: 2\n    - [one, two]\n---\nNested body"
        ) as SkillParseResult.Success
        assertEquals("First line\nSecond line", nested.document.frontmatter.description)
        assertEquals("Nested body", nested.document.body)

        listOf(
            "name: Broken\ndescription: [unterminated",
            "name: Tagged\ndescription: !!java.lang.String [unsafe]",
        ).forEach { yaml ->
            val failure = SkillFrontmatterParser.parseDocument("---\n$yaml\n---\nBody")
            assertTrue("Invalid YAML was accepted: $failure", failure is SkillParseResult.Error)
            failure as SkillParseResult.Error
            assertTrue(failure.message.isNotBlank())
            assertNotNull("Parser exception must remain diagnosable", failure.cause)
        }
    }

    @Test
    fun searchFactoriesRoundTripThroughPersistedUserConfiguration() {
        val options = SearchProviderType.entries.map { type ->
            type.createOptions().also {
                assertEquals(type, it.providerType)
                assertNotEquals("Each creation owns a new identity", it.id, type.createOptions().id)
                assertNotNull(SearchService.getService(it))
            }
        }
        val configuration = UserConfiguration(searchServices = options)
        val encoded = JsonInstant.encodeToString(configuration)
        val restored = JsonInstant.decodeFromString<UserConfiguration>(encoded)
        assertEquals(options, restored.searchServices)
        assertEquals(SearchProviderType.entries.toList(), restored.searchServices.map { it.providerType })
        restored.searchServices.forEach { assertNotNull(SearchService.getService(it)) }
    }

    @Test
    fun targetResourcesRetainFiveLanguagesAndRegionalFallbacks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expectations = mapOf(
            "en" to "Cancel", "en-GB" to "Cancel",
            "zh" to "取消", "zh-CN" to "取消", "zh-SG" to "取消",
            // values-zh provides Hans; Android does not match it to Taiwan's Hant script.
            // Without a Hant translation, the existing unqualified English resource applies.
            "zh-TW" to "Cancel",
            "ja" to "キャンセル", "ja-JP" to "キャンセル",
            "ko" to "취소", "ko-KR" to "취소",
            "ru" to "Отмена", "ru-RU" to "Отмена",
            "fr-FR" to "Cancel",
        )
        expectations.forEach { (tag, expected) ->
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(tag)))
            }
            val localized = context.createConfigurationContext(configuration)
            assertEquals("Target APK locale $tag", expected, localized.getString(R.string.cancel))
        }
    }
}
