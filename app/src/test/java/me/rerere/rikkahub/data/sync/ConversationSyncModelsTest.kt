package me.rerere.rikkahub.data.sync

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 会话增量同步「序列化模型 + 附件映射」单测。
 * 覆盖：upload 路径改写、非 upload 本地引用移除、Tool 嵌套递归、buildConversationItem、
 * index/item 编解码与 format/version 硬校验。
 */
class ConversationSyncModelsTest {

    private fun conv(nodes: List<MessageNode>, syncUpdatedAt: Long = 100L): Conversation = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "标题",
        messageNodes = nodes,
        syncUpdatedAt = syncUpdatedAt,
    )

    @Test
    fun `upload file url is rewritten to remote reference`() {
        val message = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Image(url = "file:///data/user/0/com.inonvation.rikkahub.debug/files/upload/photo.png"),
                UIMessagePart.Text("hello"),
            ),
        )
        val item = buildConversationItem(conv(listOf(MessageNode(id = Uuid.random(), messages = listOf(message)))))
        val parts = item.nodes[0].messages[0].parts
        assertEquals("upload/photo.png", (parts[0] as UIMessagePart.Image).url)
        assertEquals("hello", (parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `non-upload local file reference is dropped`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Document(url = "file:///data/user/0/x/files/other/doc.pdf", fileName = "doc.pdf"),
            ),
        )
        val item = buildConversationItem(conv(listOf(MessageNode(id = Uuid.random(), messages = listOf(message)))))
        assertTrue(item.nodes[0].messages[0].parts.isEmpty())
    }

    @Test
    fun `http data and content urls pass through`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Image(url = "https://example.com/a.png"),
                UIMessagePart.Image(url = "data:image/png;base64,xxx"),
            ),
        )
        val item = buildConversationItem(conv(listOf(MessageNode(id = Uuid.random(), messages = listOf(message)))))
        val parts = item.nodes[0].messages[0].parts
        assertEquals("https://example.com/a.png", (parts[0] as UIMessagePart.Image).url)
        assertEquals("data:image/png;base64,xxx", (parts[1] as UIMessagePart.Image).url)
    }

    @Test
    fun `tool nested output urls are rewritten recursively`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "t1",
                    toolName = "read",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Image(url = "file:///data/user/0/x/files/upload/nested.png"),
                    ),
                ),
            ),
        )
        val item = buildConversationItem(conv(listOf(MessageNode(id = Uuid.random(), messages = listOf(message)))))
        val tool = item.nodes[0].messages[0].parts[0] as UIMessagePart.Tool
        assertEquals("upload/nested.png", (tool.output[0] as UIMessagePart.Image).url)
    }

    @Test
    fun `build item carries meta and message count`() {
        val node = MessageNode(
            id = Uuid.random(),
            messages = listOf(
                UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text("a"))),
                UIMessage(id = Uuid.random(), role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("b"))),
            ),
        )
        val c = conv(listOf(node), syncUpdatedAt = 123L)
        val item = buildConversationItem(c)
        assertEquals(123L, item.syncUpdatedAt)
        assertEquals(2, item.nodes[0].messages.size)
        assertEquals(c.id.toString(), item.id)
    }

    @Test
    fun `index encode decode roundtrip and format gate`() {
        val entry = ConversationIndexEntry(
            id = "c1", syncUpdatedAt = 10L, title = "t", createAt = 1L, isPinned = false, messageCount = 3,
            path = conversationItemPath("c1"),
        )
        val index = ConversationIndex(conversations = listOf(entry), updatedAt = 5L)
        val decoded = decodeConversationIndex(encodeConversationIndex(index))
        assertEquals(index, decoded)

        assertNull(decodeConversationIndex("""{"format":"unknown","version":1}""".toByteArray()))
        assertNull(decodeConversationIndex("""{"format":"rikkahub-conversations-index","version":99}""".toByteArray()))
        assertNull(decodeConversationIndex("not json".toByteArray()))
    }

    @Test
    fun `item encode decode roundtrip and format gate`() {
        val item = buildConversationItem(conv(listOf(MessageNode(id = Uuid.random(), messages = emptyList()))))
        assertEquals(item, decodeConversationItem(encodeConversationItem(item)))
        assertNull(decodeConversationItem("""{"format":"other","version":1}""".toByteArray()))
    }

    @Test
    fun `index upsert replaces entry and sorts`() {
        val e1 = ConversationIndexEntry("a", 1L, "", 0L, false, 0, "x")
        val e2 = ConversationIndexEntry("a", 5L, "", 0L, false, 0, "x")
        val index = ConversationIndex(conversations = listOf(e1)).upsert(e2, now = 9L)
        assertEquals(1, index.conversations.size)
        assertEquals(5L, index.conversations[0].syncUpdatedAt)
    }

    @Test
    fun `remote upload url maps back to local file path`() {
        val mapped = mapRemoteUrlsToLocal(
            listOf(UIMessagePart.Image(url = "upload/photo.png")),
            "/data/files/upload",
        )
        assertEquals("file:///data/files/upload/photo.png", (mapped[0] as UIMessagePart.Image).url)
    }

    @Test
    fun `download mapping keeps non-upload urls`() {
        val parts = mapRemoteUrlsToLocal(
            listOf(UIMessagePart.Image(url = "https://x/a.png"), UIMessagePart.Text("hi")),
            "/u",
        )
        assertEquals("https://x/a.png", (parts[0] as UIMessagePart.Image).url)
        assertEquals("hi", (parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `export then download roundtrip preserves upload reference`() {
        val original = "file:///data/user/0/x/files/upload/photo.png"
        val message = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(url = original)))
        val item = buildConversationItem(conv(listOf(MessageNode(id = Uuid.random(), messages = listOf(message)))))
        val exported = (item.nodes[0].messages[0].parts[0] as UIMessagePart.Image).url
        assertEquals("upload/photo.png", exported)
        val local = mapRemoteUrlsToLocal(listOf(UIMessagePart.Image(url = exported)), "/data/user/0/x/files/upload")
        assertEquals(original, (local[0] as UIMessagePart.Image).url)
    }

    @Test
    fun `device paths in tool input are redacted on export`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "read",
            input = """{"path":"/data/user/0/x/files/upload/a.png","dir":"/data/user/0/x/files/workspaces/w1"}""",
        )
        val parts = mapPartsForExport(listOf(tool), filesRoot = "/data/user/0/x/files")
        val t = parts[0] as UIMessagePart.Tool
        assertTrue("upload 路径应保留为引用", t.input.contains("upload/a.png"))
        assertFalse("设备绝对路径应被移除", t.input.contains("/data/user/0/"))
    }

    @Test
    fun `workspace cwd is not exported`() {
        val c = Conversation(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            title = "t",
            messageNodes = emptyList(),
            workspaceCwd = "/data/user/0/x/files/workspaces/w1",
        )
        val item = buildConversationItem(c, filesRoot = "/data/user/0/x/files")
        assertNull(item.workspaceCwd)
    }

    @Test
    fun `toRemoteUrl rejects dotdot and nested names`() {
        assertNull(toRemoteUrl("file:///data/user/0/x/files/upload/../../secret"))
        assertNull(toRemoteUrl("file:///data/user/0/x/files/upload/sub/dir.png"))
        assertNull(toRemoteUrl("file:///data/user/0/x/files/other/a.png"))
    }
}
