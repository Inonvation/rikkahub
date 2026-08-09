package me.rerere.rikkahub.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WebDavSyncProvider 路径归一化（normalizeRelPath）单测。
 * 覆盖坚果云返回的两种 href 格式：完整 URL 与绝对路径。
 */
class WebDavSyncProviderTest {
    private val url = "https://dav.jianguoyun.com/dav"
    private val path = "rikkahub_backups"
    private val root = "sync"

    @Test
    fun `absolute path href is normalized`() {
        assertEquals("db", WebDavSyncProvider.normalizeRelPath("/dav/rikkahub_backups/sync/db", url, path, root))
        assertEquals(
            "db/rikka_hub.db",
            WebDavSyncProvider.normalizeRelPath("/dav/rikkahub_backups/sync/db/rikka_hub.db", url, path, root),
        )
        assertEquals(
            "upload/a.txt",
            WebDavSyncProvider.normalizeRelPath("/dav/rikkahub_backups/sync/upload/a.txt", url, path, root),
        )
    }

    @Test
    fun `full url href is normalized`() {
        assertEquals(
            "settings.json",
            WebDavSyncProvider.normalizeRelPath(
                "https://dav.jianguoyun.com/dav/rikkahub_backups/sync/settings.json",
                url,
                path,
                root,
            ),
        )
        assertEquals(
            "db",
            WebDavSyncProvider.normalizeRelPath(
                "https://dav.jianguoyun.com/dav/rikkahub_backups/sync/db",
                url,
                path,
                root,
            ),
        )
    }

    @Test
    fun `sync root itself yields empty relPath`() {
        assertEquals("", WebDavSyncProvider.normalizeRelPath("/dav/rikkahub_backups/sync", url, path, root))
        assertEquals(
            "",
            WebDavSyncProvider.normalizeRelPath(
                "https://dav.jianguoyun.com/dav/rikkahub_backups/sync",
                url,
                path,
                root,
            ),
        )
    }

    @Test
    fun `relative path without leading slash is normalized`() {
        assertEquals("db", WebDavSyncProvider.normalizeRelPath("dav/rikkahub_backups/sync/db", url, path, root))
        assertEquals(
            "settings.json",
            WebDavSyncProvider.normalizeRelPath("rikkahub_backups/sync/settings.json", url, path, root),
        )
    }

    @Test
    fun `url without dav segment still normalizes`() {
        val plainUrl = "https://example.com/webdav"
        val plainPath = ""
        assertEquals("db", WebDavSyncProvider.normalizeRelPath("/webdav/sync/db", plainUrl, plainPath, root))
    }
}
