package me.rerere.rikkahub.utils

import android.util.LruCache
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart

/**
 * 工具 JSON 解析的进程级缓存（复用 Markdown.markdownParseCache 的 LRU + 预取模式）。
 *
 * key = toolCallId（代码库已依赖其全局唯一性，O(1) 构造，无需对大输出拼串/哈希）；
 * 陈旧性不靠 key，靠 value 内的"引用锚点"：
 * 流式期间 UIMessage 每 chunk copy() 出新的 output 引用 → 判定 stale → 重解析写回
 * （保持流式实时上屏语义）；消息定型后引用跨视口稳定 → O(1) 命中。
 *
 * LazyColumn item 划出视口后组合被回收，remember 缓存失效，再划回时重新组合；
 * 本缓存是进程级、不随组合销毁，让"每次滑动经过都重复解析大 JSON"变为 O(1) 命中，
 * 配合 ChatList 的滚动预取（后台预热）消除首帧主线程同步解析。
 */
/** 工具输出文本解析为 JsonElement 的纯函数（与缓存解耦，供单测直接覆盖） */
internal fun parseToolOutputText(text: String): JsonElement =
    runCatching { JsonInstant.parseToJsonElement(text) }
        .getOrElse { JsonObject(emptyMap()) }

object ToolParseCache {
    /** content 缓存按解析源文本大小（KB）计，8MB 上限（shell stdout 单条约 128KB，约 60 条重工具消息） */
    private const val MAX_CONTENT_KB = 8 * 1024
    /** input 缓存按条数计（入参都很小） */
    private const val MAX_INPUT_ENTRIES = 256

    private class ContentEntry(
        val outputRef: Any,
        val json: JsonElement?,
        val sizeKb: Int,
    )

    private class InputEntry(
        val inputRef: Any,
        val json: JsonElement,
    )

    private val contentCache = object : LruCache<String, ContentEntry>(MAX_CONTENT_KB) {
        override fun sizeOf(key: String, value: ContentEntry): Int = value.sizeKb
    }

    private val inputCache = LruCache<String, InputEntry>(MAX_INPUT_ENTRIES)

    /**
     * 组合/预取共用：先查缓存，miss 时同步拼接+解析+写缓存。
     * 语义与 ChatMessageToolStep 原实现逐字一致；仅"命中缓存时不再主线程解析"。
     */
    fun toolOutputContent(tool: UIMessagePart.Tool): JsonElement? {
        val output = tool.output
        if (output.isEmpty()) return null
        contentCache.get(tool.toolCallId)?.let { entry ->
            if (entry.outputRef === output) return entry.json
        }
        val text = output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val json = parseToolOutputText(text)
        contentCache.put(tool.toolCallId, ContentEntry(output, json, text.length / 1024 + 1))
        return json
    }

    /** 入参解析缓存：消除 extractFileChanges / ToolUIContext 各处重复的 inputAsJson() 调用 */
    fun toolInput(tool: UIMessagePart.Tool): JsonElement {
        val input = tool.input
        inputCache.get(tool.toolCallId)?.let { entry ->
            if (entry.inputRef === input) return entry.json
        }
        val json = tool.inputAsJson()
        inputCache.put(tool.toolCallId, InputEntry(input, json))
        return json
    }
}
