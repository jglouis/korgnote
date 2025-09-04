package com.korgnote.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class KtorOrgRepository(
    private val client: HttpClient,
    private val baseUrl: String,
) : OrgRepository {
    override suspend fun fetchAllContents(): Map<String, String> {
        // Expecting a JSON object of filename -> content
        return client.get("$baseUrl/api/org-contents").body()
    }

    override suspend fun fetchTreePaths(): List<String> {
        return client.get("$baseUrl/api/org-tree-paths").body()
    }

    companion object {
        fun default(): HttpClient = platformBuildHttpClient()
    }
}

// Platform-specific client builder (Android adds Logging, others are minimal)
expect fun platformBuildHttpClient(): HttpClient
