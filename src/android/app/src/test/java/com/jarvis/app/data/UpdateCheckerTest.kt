package com.jarvis.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun endpoint(path: String): String = server.url(path).toString()

    private fun enqueueRelease(path: String, tag: String, source: String) {
        val apkUrl = "https://$source.example.com/$tag.apk"
        val body = """
            [
              {
                "tag_name": "$tag",
                "name": "Jarvis $tag",
                "body": "$source release",
                "draft": false,
                "prerelease": false,
                "assets": [
                  {
                    "name": "Jarvis-$tag.apk",
                    "browser_download_url": "$apkUrl",
                    "size": 65667787,
                    "type": "attach"
                  }
                ]
              }
            ]
        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    private fun checkSources(): JSONArray? = UpdateChecker.fetchReleasesJson(
        endpoint("/atomgit"),
        endpoint("/github"),
        endpoint("/gitee"),
    )

    @Test
    fun `AtomGit is preferred and its APK metadata is preserved`() = runBlocking {
        enqueueRelease("/atomgit", "v0.0.6", "atomgit")

        val result = checkSources()

        assertEquals("v0.0.6", result?.getJSONObject(0)?.getString("tag_name"))
        assertEquals(
            "https://atomgit.example.com/v0.0.6.apk",
            result?.getJSONObject(0)?.getJSONArray("assets")?.getJSONObject(0)
                ?.getString("browser_download_url"),
        )
        assertEquals("/atomgit", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `falls back from AtomGit to GitHub without contacting Gitee`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        enqueueRelease("/github", "v0.0.7", "github")

        val result = checkSources()

        assertEquals("v0.0.7", result?.getJSONObject(0)?.getString("tag_name"))
        assertEquals("/atomgit", server.takeRequest().path)
        assertEquals("/github", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `uses Gitee as the final fallback in source order`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(403))
        enqueueRelease("/gitee", "v0.0.8", "gitee")

        val result = checkSources()

        assertEquals("v0.0.8", result?.getJSONObject(0)?.getString("tag_name"))
        assertEquals(
            listOf("/atomgit", "/github", "/gitee"),
            listOf(server.takeRequest().path, server.takeRequest().path, server.takeRequest().path),
        )
    }

    @Test
    fun `throws network failure when every source fails`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }

        assertThrows(IOException::class.java) {
            runBlocking { checkSources() }
        }
        assertTrue(server.requestCount >= 3)
    }
}
