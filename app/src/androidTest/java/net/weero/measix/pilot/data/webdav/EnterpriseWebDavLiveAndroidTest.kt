package net.weero.measix.pilot.data.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.webdav.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in on an unbound test device; the host controls only its isolated Core fixture. */
@RunWith(AndroidJUnit4::class)
class EnterpriseWebDavLiveAndroidTest {
    @Test fun realCoreRelayDavRoundTripAndLivePermissionChanges(): Unit = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        val inputPath = arguments.getString("coreWebDavInput")
        assumeTrue(inputPath != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputFile = File(requireNotNull(inputPath)).canonicalFile
        require(inputFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        val input = try { JSONObject(inputFile.readText()) } finally { check(inputFile.delete()) }
        val koin = GlobalContext.get()
        koin.get<ApplicationRecoveryCoordinator>()
        withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() }
        val sessions = koin.get<EnterpriseSessionController>()
        val enterprise = koin.get<EnterpriseApplicationService>()
        val queries = koin.get<ConfigurationQueryService>()
        val dav = koin.get<WebDavApplicationService>()
        val platform = koin.get<PlatformEnterpriseService>()
        val initial = (sessions.state.value as EnterpriseState.Available).manifest
        check(initial.session == null && initial.pendingEnrollment == null && initial.lastIdentity == null) {
            "core_webdav_requires_unbound_test_device"
        }
        val personalBefore = queries.read(RealmAccess.Personal).let {
            it.personalWebDavConnections to it.currentPersonalWebDavConnectionId
        }
        val evidence = JSONObject().put("server", input.getString("server"))
        val folder = "android-core-dav-${UUID.randomUUID()}"
        val phase = File(context.cacheDir, "core-webdav-phase")
        val acknowledged = File(context.cacheDir, "core-webdav-ack")
        var handle: WebDavHandle? = null
        var ownAccess: RealmAccess.Enterprise? = null
        var created = false
        var failure: Throwable? = null
        suspend fun awaitHost(name: String) {
            phase.writeText(name)
            withTimeout(120_000) {
                while (!acknowledged.exists() || acknowledged.readText().trim() != name) delay(250)
            }
        }
        try {
            withTimeout(600_000) {
                val material = EnrollmentMaterialParser().parse(input.getString("enrollment"))
                val access = platform.enroll(material, android.os.Build.MODEL,
                    net.weero.measix.pilot.BuildConfig.VERSION_NAME).also { ownAccess = it }
                assertEquals(EnterpriseSynchronizationCommandResult.COMPLETED, enterprise.synchronize(access))
                val previous = requireNotNull(sessions.readPresentation().selection)
                if (previous.access != access) sessions.switchRealm(RealmSwitchRequest(previous, access)) {}
                val selection = requireNotNull(sessions.readPresentation().selection)
                var opened = dav.open(selection).also { handle = it }
                val target = opened.target as WebDavTarget.Enterprise
                assertEquals(input.getString("resourceId"), target.id)
                assertEquals(input.getString("agentSpaceId"), target.space)
                assertTrue(opened.writable)
                assertTrue(queries.read(access).enterpriseConfiguration!!.webdav.any { it.id == target.id })
                dav.mkdir(opened, folder)
                created = true
                val path = "$folder/中文 space #%.bin"
                val bytes = ByteArray(1024 * 1024) { (it * 31 + it / 251).toByte() }
                dav.upload(opened, path, null, { bytes.inputStream() })
                val uploaded = requireNotNull(dav.stat(opened, path))
                assertEquals(bytes.size.toLong(), uploaded.size)
                assertTrue(WebDavClient.strongEtag(uploaded.etag))
                val output = ByteArrayOutputStream()
                assertEquals(bytes.size.toLong(), dav.download(opened, path, output, uploaded.etag))
                assertArrayEquals(bytes, output.toByteArray())
                evidence.put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    .put("roundTripBytes", bytes.size).put("designatedResourceMatched", true)
                try {
                    dav.upload(opened, path, 1, { byteArrayOf(0).inputStream() })
                    fail("create-only upload overwrote an existing file")
                } catch (expected: DavHttpException) { assertEquals(412, expected.status) }
                evidence.put("createOnlyConflict", true)
                // The fixture issues 15-second tokens. Keep one real admitted PUT active past expiry.
                val longPath = "$folder/long-stream.bin"
                val longBytes = 20L * 1024 * 1024
                val sentDigest = MessageDigest.getInstance("SHA-256")
                val started = android.os.SystemClock.elapsedRealtime()
                dav.upload(opened, longPath, longBytes, {
                    object : InputStream() {
                        var remaining = longBytes
                        override fun read(): Int = error("Buffered read required")
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (remaining == 0L) return -1
                            val count = minOf(length.toLong(), remaining).toInt()
                            Thread.sleep(55)
                            buffer.fill(37, offset, offset + count)
                            sentDigest.update(buffer, offset, count)
                            remaining -= count
                            return count
                        }
                    }
                })
                val elapsed = android.os.SystemClock.elapsedRealtime() - started
                assertTrue("Upload must span the fixture access-token lifetime", elapsed > 15_000)
                val receivedDigest = MessageDigest.getInstance("SHA-256")
                val discard = object : OutputStream() {
                    override fun write(value: Int) = Unit
                    override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
                }
                val longFile = requireNotNull(dav.stat(opened, longPath))
                assertEquals(longBytes, dav.download(opened, longPath, DigestOutputStream(discard, receivedDigest), longFile.etag))
                assertArrayEquals(sentDigest.digest(), receivedDigest.digest())
                evidence.put("admittedLongUploadMillis", elapsed).put("longStreamBytes", longBytes)
                val beforeToken = platform.accessToken(access.sessionId, target.connection)
                val untilExpiry = beforeToken.expiresAtMillis - System.currentTimeMillis() + 1000
                require(untilExpiry in 0..60_000) { "fixture_requires_short_access_token" }
                delay(untilExpiry)
                assertEquals(uploaded.size, dav.stat(opened, path)?.size)
                val afterToken = platform.accessToken(access.sessionId, target.connection)
                assertTrue("Expired access token must be replaced", beforeToken.value != afterToken.value)
                evidence.put("shortTokenRefresh", true)
                awaitHost("read-only")
                assertFalse(dav.isValid(opened))
                dav.close(opened)
                opened = dav.open(selection).also { handle = it }
                assertFalse(opened.writable)
                output.reset()
                dav.download(opened, path, output, uploaded.etag)
                assertArrayEquals(bytes, output.toByteArray())
                var inputOpened = false
                try {
                    dav.upload(opened, "$folder/denied.bin", 1, { inputOpened = true; byteArrayOf(0).inputStream() })
                    fail("read-only DAV accepted a write")
                } catch (expected: IllegalStateException) { assertEquals("webdav_read_only", expected.message) }
                assertFalse(inputOpened)
                evidence.put("readOnlyDownloadAndWriteDenied", true).put("oldBindingRejected", true)
                awaitHost("full")
                dav.close(opened)
                opened = dav.open(selection).also { handle = it }
                assertTrue(opened.writable)
                dav.mutate(opened, folder, "DELETE", directory = true)
                created = false
                assertNull(dav.stat(opened, folder))
                evidence.put("ownedRemoteFilesRemoved", true)
                assertEquals(personalBefore, queries.read(RealmAccess.Personal).let {
                    it.personalWebDavConnections to it.currentPersonalWebDavConnectionId
                })
                evidence.put("personalConfigurationUnchanged", true)
            }
        } catch (error: Throwable) {
            failure = error
            evidence.put("failure", error.stackTraceToString())
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeout(45_000) {
                        val current = handle
                        if (created && current != null) dav.mutate(current, folder, "DELETE", directory = true)
                    }
                } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: run { failure = cleanup } }
                try {
                    handle?.let { dav.close(it) }
                    if (ownAccess == null) {
                        val committed = (sessions.state.value as EnterpriseState.Available).manifest.session
                        if (committed != null) {
                            check(committed.platform?.connection?.origin == EnrollmentMaterialParser()
                                .parse(input.getString("enrollment")).platformOrigin)
                            ownAccess = RealmAccess.Enterprise(committed.identity.scope, committed.id)
                        }
                    }
                    if (ownAccess != null) {
                        val exit = requireNotNull(enterprise.captureExitRequest())
                        check(exit.access == ownAccess)
                        val result = withTimeout(60_000) { enterprise.exit(exit) }
                        assertNull(result.remoteLogoutFailure)
                        assertNull(result.maintenanceFailure)
                        assertNull((sessions.state.value as EnterpriseState.Available).manifest.session)
                        evidence.put("formalExitCompleted", true)
                    } else {
                        // A failed Bootstrap can leave this test's exchanged Session pending.
                        val pending = sessions.pendingPlatformEnrollment()
                        if (pending != null) withTimeout(60_000) {
                            check(pending.platform.connection.origin == EnrollmentMaterialParser()
                                .parse(input.getString("enrollment")).platformOrigin)
                            val control = koin.get<PlatformControlClient>()
                            var logout = sessions.capturePendingPlatformLogout(pending)
                            if (logout.credential.pendingIdempotencyKey != null) {
                                val attempt = sessions.beginPlatformRefresh(pending.sessionId)
                                val response = control.refresh(logout.connection, attempt.credential.refreshToken,
                                    requireNotNull(attempt.credential.pendingIdempotencyKey))
                                sessions.acceptPlatformRefresh(attempt, response)
                                logout = sessions.capturePendingPlatformLogout(requireNotNull(sessions.pendingPlatformEnrollment()))
                            }
                            control.logout(logout.connection, logout.credential.refreshToken)
                            sessions.abandonPendingPlatformEnrollment(pending.sessionId)
                            evidence.put("ownedPendingEnrollmentRemoved", true)
                        }
                    }
                } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: run { failure = cleanup } }
                phase.delete()
                acknowledged.delete()
                failure?.let { evidence.put("failure", it.stackTraceToString()) }
                File(context.cacheDir, "core-webdav-live-evidence.json").writeText(evidence.toString(2))
            }
        }
        failure?.let { throw it }
    }
}
