package me.rerere.ai.util

/**
 * 分隔符：空白（空格、换行、制表等）或逗号。
 * 存储与解析两端共用这一份定义，避免 UI 写进去的格式与请求时解析的格式不一致。
 */
val SPLIT_KEY_REGEX: Regex = "[\\s,]+".toRegex()

/**
 * 把一段 key 文本拆成多个 key，并去重、去空白。
 * 支持空格、换行、逗号分隔。多个 key 在底层就是这样一个拼接字符串。
 */
fun splitApiKeys(keys: String): List<String> {
    return keys
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}
