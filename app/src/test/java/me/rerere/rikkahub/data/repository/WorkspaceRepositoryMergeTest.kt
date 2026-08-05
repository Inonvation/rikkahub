package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 AGENTS.md 自动生成区(AUTOGEN 区)的合并逻辑:
 * - 只替换 BEGIN..END 之间的内容, END 之后(AI 自由编辑区)原样保留;
 * - 老文件无标记时 AUTOGEN 块插到最前并保留原内容;
 * - 内容稳定时替换结果与原文件逐字节一致(ensureAgentsFile 据此跳过写文件, 不破坏 prompt 缓存)。
 */
class WorkspaceRepositoryMergeTest {
    private val gen = "# Workspace Environment\nDistro: Ubuntu 24.04\n"

    @Test
    fun emptyOrNullExistingReturnsPureBlock() {
        val expected = "<!-- AUTOGEN-BEGIN -->\n$gen<!-- AUTOGEN-END -->"
        assertEquals(expected, mergeAgentsContent(null, gen))
        assertEquals(expected, mergeAgentsContent("", gen))
        assertEquals(expected, mergeAgentsContent("   \n  ", gen))
    }

    @Test
    fun replacesAutogenSectionAndKeepsAiNotes() {
        val existing = "<!-- AUTOGEN-BEGIN -->\nold\n<!-- AUTOGEN-END -->\n\n## My notes\npip install scipy worked\n"
        val expected = "<!-- AUTOGEN-BEGIN -->\n$gen<!-- AUTOGEN-END -->\n\n## My notes\npip install scipy worked\n"
        assertEquals(expected, mergeAgentsContent(existing, gen))
    }

    @Test
    fun identicalContentIsStableByteForByte() {
        // 环境无变化时, 刷新结果应与现有文件逐字节一致 → ensureAgentsFile 跳过写文件, prompt 缓存前缀不变
        val existing = "<!-- AUTOGEN-BEGIN -->\n$gen<!-- AUTOGEN-END -->"
        assertEquals(existing, mergeAgentsContent(existing, gen))
    }

    @Test
    fun legacyFileWithoutMarkersGetsBlockPrepended() {
        val existing = "# old manually written file\nkeep me\n"
        val expected = "<!-- AUTOGEN-BEGIN -->\n$gen<!-- AUTOGEN-END -->\n\n# old manually written file\nkeep me\n"
        assertEquals(expected, mergeAgentsContent(existing, gen))
    }

    @Test
    fun brokenMarkerFallsBackToPrependKeepingOriginal() {
        // AI 删掉了 END 标记: 下次刷新把 AUTOGEN 块插到最前并保留原内容(自愈边界, 不丢数据)
        val existing = "<!-- AUTOGEN-BEGIN -->\nold without end\n"
        val expected = "<!-- AUTOGEN-BEGIN -->\n$gen<!-- AUTOGEN-END -->\n\n$existing"
        assertEquals(expected, mergeAgentsContent(existing, gen))
    }
}
