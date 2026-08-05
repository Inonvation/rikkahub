package me.rerere.rikkahub.data.trustedfolders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** SafeFolderAccess 相对路径校验：AI 只能操作信任文件夹内，任何越界路径必须被拒绝 */
class TrustedFolderPathTest {

    @Test
    fun `empty path means root`() {
        assertEquals("", SafeFolderAccess.validateRelPath(""))
        assertEquals("", SafeFolderAccess.validateRelPath("   "))
    }

    @Test
    fun `normal relative path passes through`() {
        assertEquals("notes/diary.md", SafeFolderAccess.validateRelPath("notes/diary.md"))
        assertEquals("a/b/c", SafeFolderAccess.validateRelPath("a/b/c"))
    }

    @Test
    fun `backslash normalized to slash`() {
        assertEquals("notes/diary.md", SafeFolderAccess.validateRelPath("notes\\diary.md"))
    }

    @Test
    fun `absolute path rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeFolderAccess.validateRelPath("/data/secret")
        }
    }

    @Test
    fun `parent traversal rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeFolderAccess.validateRelPath("../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeFolderAccess.validateRelPath("notes/../../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeFolderAccess.validateRelPath("a/..")
        }
    }

    @Test
    fun `dot segments rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeFolderAccess.validateRelPath("./secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafeFolderAccess.validateRelPath("notes/./diary.md")
        }
    }

    @Test
    fun `double slash rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            SafeFolderAccess.validateRelPath("notes//diary.md")
        }
    }
}
