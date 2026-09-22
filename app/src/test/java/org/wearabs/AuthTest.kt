package org.wearabs

import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.junit.Test
import org.wearabs.net.LoginRequest
import org.wearabs.net.LoginResponse
import org.wearabs.net.normalizeServerUrl

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

class AuthTest {

    @Test
    fun `server address is normalised the way people type it`() {
        assertEquals("https://abs.example.com", normalizeServerUrl("abs.example.com"))
        assertEquals("https://abs.example.com", normalizeServerUrl("https://abs.example.com"))
        assertEquals("https://abs.example.com", normalizeServerUrl("https://abs.example.com/"))
        assertEquals("https://abs.example.com", normalizeServerUrl("  abs.example.com/  "))
        // An explicit http:// is kept — some servers are plain HTTP on a LAN.
        assertEquals("http://192.168.1.10:13378", normalizeServerUrl("http://192.168.1.10:13378/"))
        assertEquals("https://abs.example.com/audiobooks", normalizeServerUrl("abs.example.com/audiobooks"))
    }

    @Test
    fun `login request carries exactly the two fields passport reads`() {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest("bob", "s3cret"))
        assertEquals("""{"username":"bob","password":"s3cret"}""", body)
    }

    /**
     * Shaped after Auth.handleLoginSuccess in 2.36.1: the tokens are attached to
     * the user object, and refreshToken is only present with x-return-tokens.
     */
    @Test
    fun `login response yields both tokens and the default library`() {
        val payload = """
        {
          "user": {
            "id": "usr_1", "username": "bob", "type": "user",
            "accessToken": "access-abc", "refreshToken": "refresh-xyz",
            "mediaProgress": [], "bookmarks": [], "permissions": {}
          },
          "userDefaultLibraryId": "lib_1",
          "serverSettings": { "scannerFindCovers": false },
          "ereaderDevices": [],
          "Source": "docker"
        }
        """.trimIndent()

        val parsed = json.decodeFromString<LoginResponse>(payload)
        assertEquals("access-abc", parsed.user.accessToken)
        assertEquals("refresh-xyz", parsed.user.refreshToken)
        assertEquals("bob", parsed.user.username)
        assertEquals("lib_1", parsed.userDefaultLibraryId)
    }

    /** A refresh without x-refresh-token returns refreshToken: null, not an error. */
    @Test
    fun `refresh response with a null refresh token still parses`() {
        val payload = """
        { "user": { "id": "usr_1", "username": "bob",
                    "accessToken": "access-new", "refreshToken": null },
          "userDefaultLibraryId": null }
        """.trimIndent()

        val parsed = json.decodeFromString<LoginResponse>(payload)
        assertEquals("access-new", parsed.user.accessToken)
        assertEquals(null, parsed.user.refreshToken)
        assertEquals(null, parsed.userDefaultLibraryId)
    }
}
