package me.rerere.rikkahub.data.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseCheckerTest {

    private val checker = GitHubReleaseChecker()

    @Test
    fun parsesReleaseWithArm64ApkAsset() {
        val release = checker.parseRelease(
            """
            {
              "tag_name": "2.13.0",
              "html_url": "https://github.com/Inonvation/rikkahub/releases/tag/2.13.0",
              "assets": [
                {"name": "universal.apk", "browser_download_url": "https://example.com/universal.apk"},
                {"name": "app-arm64-v8a-2.13.0.apk", "browser_download_url": "https://example.com/arm64.apk"},
                {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"}
              ]
            }
            """.trimIndent(),
        )
        assertNotNull(release)
        assertEquals("2.13.0", release!!.tagName)
        assertEquals("https://github.com/Inonvation/rikkahub/releases/tag/2.13.0", release.htmlUrl)
        assertEquals("https://example.com/arm64.apk", release.apkUrl)
    }

    @Test
    fun parseFailureReturnsNull() {
        assertNull(checker.parseRelease("not json"))
        assertNull(checker.parseRelease("""{"html_url":"https://example.com"}"""))
    }

    @Test
    fun versionComparison() {
        assertTrue(checker.compareVersionNames("2.13.0", "2.12.1") > 0)
        assertTrue(checker.compareVersionNames("2.13", "2.12.1") > 0)
        assertTrue(checker.compareVersionNames("2.12.1", "2.12.1") == 0)
        assertTrue(checker.compareVersionNames("2.12", "2.12.0") == 0)
        assertTrue(checker.compareVersionNames("2.12.10", "2.12.9") > 0)
        assertTrue(checker.compareVersionNames("2.12.1", "2.13.0") < 0)
    }

    @Test
    fun versionComparisonIgnoresNonDigits() {
        // tag 带 v/后缀等杂质时段内非数字按 0 处理，保证比较不抛异常
        assertEquals(0, checker.compareVersionNames("v2.12.1", "2.12.1"))
    }
}
