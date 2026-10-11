package net.weero.measix.pilot.data.enterprise

import java.io.IOException
import net.weero.measix.pilot.data.webdav.DavHttpException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class EnterpriseDavHttpFailureTest {
    private fun response(status: Int, body: String, type: String = "application/problem+json") = Response.Builder()
        .request(Request.Builder().url("https://core.example/file").build()).protocol(Protocol.HTTP_1_1)
        .code(status).message("Failure").header("Content-Type", type).body(body.toResponseBody()).build()

    @Test fun `ordinary DAV 404 remains distinct from platform problem 404`() {
        response(404, "not found", "text/plain").use {
            assertTrue(enterpriseDavHttpFailure(it, "file") is DavHttpException)
        }
        response(404, """{"type":"about:blank","title":"Missing resource","status":404,"code":"resource_unavailable","forwarded":false}""").use {
            val failure = enterpriseDavHttpFailure(it, "file") as PlatformHttpException
            assertEquals("resource_unavailable", failure.problem!!.code)
            assertEquals(false, failure.problem.forwarded)
        }
    }

    @Test fun `oversized malformed and mismatched problems never become a replay authorization`() {
        response(401, "x".repeat(65537)).use {
            val failure = enterpriseDavHttpFailure(it, "file")
            assertTrue(failure is IOException)
            assertFalse(failure is PlatformHttpException)
            assertTrue(failure.message!!.contains("too_large"))
        }
        response(401, "{invalid").use {
            val failure = enterpriseDavHttpFailure(it, "file")
            assertNotNull(failure.cause)
            assertFalse(failure is PlatformHttpException)
        }
        response(401, """{"type":"about:blank","title":"Mismatch","status":403,"code":"invalid_session","forwarded":false}""").use {
            assertTrue(enterpriseDavHttpFailure(it, "file").message!!.contains("status_mismatch"))
        }
    }
}
