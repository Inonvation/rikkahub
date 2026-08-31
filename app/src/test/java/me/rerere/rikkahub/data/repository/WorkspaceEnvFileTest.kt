package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** workspace_set_env 的持久化 env 文件行变换（纯函数） */
class WorkspaceEnvFileTest {

    @Test
    fun `empty file appends export line with quoting`() {
        assertEquals(
            listOf("export MIRROR='https://mirrors.aliyun.com'"),
            upsertEnvLine(emptyList(), "MIRROR", "https://mirrors.aliyun.com"),
        )
    }

    @Test
    fun `replaces existing key and keeps other lines`() {
        val lines = listOf(
            "# header",
            "export OLD='v1'",
            "export PATH='/usr/bin:/bin'",
        )
        val updated = upsertEnvLine(lines, "OLD", "v2")
        assertEquals(
            listOf("# header", "export PATH='/usr/bin:/bin'", "export OLD='v2'"),
            updated,
        )
    }

    @Test
    fun `escapes single quotes in value`() {
        val updated = upsertEnvLine(emptyList(), "TOKEN", "a'b\"c")
        assertEquals(listOf("export TOKEN='a'\\''b\"c'"), updated)
    }

    @Test
    fun `null value removes the key`() {
        val lines = listOf(
            "export KEEP='1'",
            "export GONE='2'",
        )
        assertEquals(listOf("export KEEP='1'"), upsertEnvLine(lines, "GONE", null))
        // 已不存在时保持原样
        assertEquals(lines, upsertEnvLine(lines, "MISSING", null))
    }

    @Test
    fun `removes any pre-existing line shape of the same key`() {
        val lines = listOf(
            "  export BAD='x'",
            "export BARE",
        )
        // 覆盖带缩进的历史行；不同 key 的裸行（export BARE）应保留
        assertEquals(listOf("export BARE", "export BAD='new'"), upsertEnvLine(lines, "BAD", "new"))
        // 删除裸 export 行定义的 key 时，只移除该行，其余原样保留
        assertEquals(listOf("  export BAD='x'"), upsertEnvLine(lines, "BARE", null))
    }
}