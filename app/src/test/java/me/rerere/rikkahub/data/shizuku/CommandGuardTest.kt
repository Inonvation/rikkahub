package me.rerere.rikkahub.data.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandGuardTest {

    @Test
    fun `empty command is blocked`() {
        assertNotNull(CommandGuard.check(emptyList()))
    }

    @Test
    fun `forbidden binary is blocked`() {
        assertNotNull(CommandGuard.check(listOf("sh", "-c", "rm -rf /")))
        assertNotNull(CommandGuard.check(listOf("su", "-c", "id")))
    }

    @Test
    fun `unknown binary is blocked`() {
        assertNotNull(CommandGuard.check(listOf("reboot")))
        assertNotNull(CommandGuard.check(listOf("whoami")))
    }

    @Test
    fun `read-only commands are allowed`() {
        assertNull(CommandGuard.check(listOf("getprop", "ro.build.version.release")))
        assertNull(CommandGuard.check(listOf("df", "-h")))
        assertNull(CommandGuard.check(listOf("dumpsys", "battery")))
        assertNull(CommandGuard.check(listOf("pm", "list", "packages", "-d")))
        assertNull(CommandGuard.check(listOf("settings", "get", "system", "screen_brightness")))
    }

    @Test
    fun `write subcommands blocked without allowWrite`() {
        assertNotNull(CommandGuard.check(listOf("pm", "disable-user", "--user", "0", "com.example")))
        assertNotNull(CommandGuard.check(listOf("pm", "trim-caches", "1000000")))
        assertNotNull(CommandGuard.check(listOf("settings", "put", "system", "screen_brightness", "128")))
    }

    @Test
    fun `write subcommands allowed with allowWrite`() {
        assertNull(CommandGuard.check(listOf("pm", "disable-user", "--user", "0", "com.example"), allowWrite = true))
        assertNull(CommandGuard.check(listOf("pm", "trim-caches", "1000000"), allowWrite = true))
        assertNull(CommandGuard.check(listOf("pm", "enable", "com.example"), allowWrite = true))
    }

    @Test
    fun `rm requires allowWrite`() {
        assertNotNull(CommandGuard.check(listOf("rm", "-f", "/sdcard/a.txt")))
        assertNull(CommandGuard.check(listOf("rm", "-f", "/sdcard/a.txt"), allowWrite = true))
    }

    @Test
    fun `find dangerous args blocked`() {
        assertNotNull(CommandGuard.check(listOf("find", "/sdcard", "-type", "f", "-delete")))
        assertNotNull(CommandGuard.check(listOf("find", "/sdcard", "-exec", "rm", "{}", ";")))
    }

    @Test
    fun `find safe args allowed`() {
        assertNull(CommandGuard.check(listOf("find", "/sdcard", "-type", "f", "-size", "+100M")))
    }

    @Test
    fun `allowed binary in any position after path prefix`() {
        // binary 允许带路径前缀（如 /system/bin/df），只取 basename 校验
        assertNull(CommandGuard.check(listOf("/system/bin/df", "-h")))
    }
}