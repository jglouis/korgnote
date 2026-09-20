package com.korgnote.app.data

import com.korgnote.common.fs.Folder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders

class KtorOrgRepository(
    private val client: HttpClient,
    private val baseUrlProvider: () -> String,
    private val tokenProvider: () -> String = { "" },
) : OrgRepository {
    private fun baseUrl() = baseUrlProvider()

    /**
     * Adds the bearer token when one is configured.
     *
     * Read per request rather than captured once, so changing it in settings takes effect without
     * restarting the app.
     */
    private fun HttpRequestBuilder.authorize() {
        val token = tokenProvider().trim()
        if (token.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $token")
    }

    override suspend fun fetchAllContents(): Map<String, String> {
        // A JSON object of filename -> raw file text
        return client.get("${baseUrl()}/api/org-content") { authorize() }.body()
    }

    override suspend fun fetchTree(): Folder {
        return client.get("${baseUrl()}/api/org-tree-paths") { authorize() }.body()
    }

    override suspend fun fetchRawContent(path: String): String {
        return client.get("${baseUrl()}/api/content/$path") { authorize() }.body()
    }

    override suspend fun saveContent(path: String, content: String) {
        client.post("${baseUrl()}/api/content/$path") {
            authorize()
            setBody(content)
        }
    }
}
