package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool

/**
 * 工具数组的规范化排序（发送给 provider 前的唯一出口约定）。
 *
 * 为什么排序：OpenAI / DeepSeek 等的隐式前缀缓存以完整请求前缀为键，tools 数组顺序参与其中。
 * 主聊（ChatService）、子代理（SubAgentToolAssembler）、群聊（DiscussionToolAssembler）三条
 * 装配路径各自的 buildList 书写顺序不同，同一能力集合可能产生不同排列 → 无谓的缓存 miss；
 * 且代码演进中调整注册顺序也会静默打穿线上缓存。按 (工具族枚举序, 工具名字典序) 排序后，
 * 排列只由能力集合本身决定，对调用方与重构免疫。
 */
fun List<Tool>.canonicalToolOrder(): List<Tool> =
    sortedWith(compareBy({ classifyToolFamily(it.name).ordinal }, { it.name }))
