package net.weero.measix.pilot.data.enterprise

import java.io.IOException
import net.weero.measix.pilot.data.webdav.DavHttpException
import okhttp3.Response

/** Installed only for platform-owned DAV requests, before stat handles an ordinary DAV 404. */
internal fun enterpriseDavHttpFailure(response: Response, path: String): IOException {
    if (response.header("Content-Type")?.substringBefore(';')?.trim() != "application/problem+json") {
        return DavHttpException(response.code, response.request.method, path)
    }
    val source = response.body.source()
    if (source.request(64L * 1024 + 1)) return IOException("webdav_platform_problem_too_large: HTTP ${response.code}")
    val raw = source.readUtf8()
    val problem = try { PlatformWireCodec.decode<PlatformProblem>(raw) }
    catch (failure: IllegalArgumentException) { return IOException("webdav_platform_problem_invalid: HTTP ${response.code}", failure) }
    catch (failure: IllegalStateException) { return IOException("webdav_platform_problem_invalid: HTTP ${response.code}", failure) }
    if (problem.status != response.code.toLong()) return IOException("webdav_platform_problem_status_mismatch: HTTP ${response.code}")
    return PlatformHttpException(response.code, problem, listOfNotNull(problem.detail ?: problem.title, problem.requestId).joinToString("; "))
}
