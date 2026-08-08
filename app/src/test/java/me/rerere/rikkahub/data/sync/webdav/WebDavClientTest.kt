package me.rerere.rikkahub.data.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavClientTest {

    @Test
    fun `jianguoyun host detected with bare dav host`() {
        assertTrue(WebDavClient.isJianguoyunHost("https://dav.jianguoyun.com"))
    }

    @Test
    fun `jianguoyun host detected with subdomain`() {
        assertTrue(WebDavClient.isJianguoyunHost("https://x.dav.jianguoyun.com"))
    }

    @Test
    fun `non jianguoyun host not detected`() {
        assertFalse(WebDavClient.isJianguoyunHost("https://dav.example.com"))
    }

    @Test
    fun `invalid url not detected`() {
        assertFalse(WebDavClient.isJianguoyunHost("not a url"))
    }

    @Test
    fun `bare jianguoyun base gets dav prefix with trailing slash`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/",
            WebDavClient.normalizeBase("https://dav.jianguoyun.com", "")
        )
    }

    @Test
    fun `url already containing dav not duplicated and keeps trailing slash`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/",
            WebDavClient.normalizeBase("https://dav.jianguoyun.com/dav", "")
        )
    }

    @Test
    fun `url with dav trailing slash normalized consistently`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/",
            WebDavClient.normalizeBase("https://dav.jianguoyun.com/dav/", "")
        )
    }

    @Test
    fun `non jianguoyun base not modified`() {
        assertEquals(
            "https://dav.example.com",
            WebDavClient.normalizeBase("https://dav.example.com", "")
        )
    }
}
