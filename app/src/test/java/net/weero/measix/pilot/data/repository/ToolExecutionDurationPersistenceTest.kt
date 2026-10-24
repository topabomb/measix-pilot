package net.weero.measix.pilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.entity.*
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.service.runtime.ConversationMutation
import net.weero.measix.pilot.service.runtime.ExecutionFacts
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ToolExecutionDurationPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private val conversationId = Uuid.random()
    private val turnId = Uuid.random().toString()

    @Before fun setup() = kotlinx.coroutines.runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val artifacts = mockk<ArtifactStore>()
        coEvery { artifacts.withLifecycleLock<Any>(any()) } coAnswers { firstArg<suspend () -> Any>().invoke() }
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, mockk(relaxed = true), database.turnExecutionDao(), database.toolExecutionDao(),
            database.conversationModelContextDao(), artifacts)
        database.conversationDao().insert(ConversationEntity(id = conversationId.toString(), assistantId = "assistant",
            title = "duration", createAt = 0, updateAt = 0, chatSuggestions = "[]", isPinned = false))
        database.turnExecutionDao().insert(TurnExecutionEntity(turnId, conversationId.toString(), Uuid.random().toString(),
            TurnExecutionStatus.RUNNING, null, 0, 0))
        Unit
    }
    @After fun teardown() { database.close() }

    private fun started() = ToolExecutionEntity(executionId = Uuid.random().toString(), turnId = turnId,
        stepId = Uuid.random().toString(), localCallId = Uuid.random().toString(), status = ToolExecutionStatus.STARTED,
        reason = null, createdAt = 0, updatedAt = 0)

    private suspend fun commit(execution: ToolExecutionEntity) {
        repository.applyMutation(ConversationMutation(conversationId, null, emptyList(), emptyList(), 0, emptyList(),
            indexForSearch = false), ExecutionFacts(null, execution))
    }

    @Test fun `terminal duration commits once and is available through the read projection`() = runTest {
        val execution = started()
        commit(execution)
        assertNull(database.toolExecutionDao().getById(execution.executionId)!!.durationMillis)
        val completed = execution.copy(status = ToolExecutionStatus.COMPLETED, durationMillis = 234)
        commit(completed)
        commit(completed.copy(durationMillis = 999))
        assertEquals(234L, database.toolExecutionDao().getById(execution.executionId)!!.durationMillis)
        assertEquals(listOf(234L), repository.getToolDurations(conversationId).values.toList())
    }

    @Test fun `rollback and recovery do not manufacture elapsed execution`() = runTest {
        val execution = started()
        commit(execution)
        val failure = runCatching {
            database.withTransaction {
                commit(execution.copy(status = ToolExecutionStatus.FAILED, durationMillis = 500))
                error("rollback")
            }
        }.exceptionOrNull()
        assertNotNull(failure)
        val retained = database.toolExecutionDao().getById(execution.executionId)!!
        assertEquals(ToolExecutionStatus.STARTED, retained.status)
        assertNull(retained.durationMillis)
        database.toolExecutionDao().transitionStartedByTurn(turnId, ToolExecutionStatus.UNKNOWN, "recovered", 600)
        assertNull(database.toolExecutionDao().getById(execution.executionId)!!.durationMillis)
        assertTrue(repository.getToolDurations(conversationId).isEmpty())
    }
}
