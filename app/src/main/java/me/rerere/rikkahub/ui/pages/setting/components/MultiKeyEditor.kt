package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AddCircle
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.JetbrainsMono

/**
 * 拆分/合并 key 文本的工具函数
 */
private val SPLIT_KEY_REGEX = "[\\s,]+".toRegex()

/**
 * 把一段 key 文本拆成多个 key，并去重、去空白。
 * 支持空格、换行、逗号分隔。
 */
internal fun splitKeys(keys: String): List<String> {
    return keys
        .split(SPLIT_KEY_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

/**
 * 多个 key 的列表编辑组件。
 * 每个 key 一行输入框，行尾可删除，底部「添加 key」按钮。
 * 粘贴多 key 文本会自动拆分。重复 key 自动去重。
 */
@Composable
internal fun MultiKeyEditor(
    keys: String,
    onKeysChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyList = remember(keys) { splitKeys(keys) }
    var lastInput by rememberSaveable { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        keyList.forEach { key ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { changed ->
                        if (SPLIT_KEY_REGEX.containsMatchIn(changed)) {
                            val newKeys = splitKeys(keys + "\n" + changed)
                            onKeysChange(newKeys.joinToString("\n"))
                        } else {
                            val updated = keyList.map { if (it == key) changed else it }
                            onKeysChange(updated.joinToString("\n"))
                        }
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val updated = keyList.filterNot { it == key }
                        onKeysChange(updated.joinToString("\n"))
                    }
                ) {
                    Icon(HugeIcons.Cancel01, contentDescription = "删除 key")
                }
            }
        }

        OutlinedTextField(
            value = lastInput,
            onValueChange = { changed ->
                lastInput = changed
                if (SPLIT_KEY_REGEX.containsMatchIn(changed)) {
                    val combined = splitKeys(keys + "\n" + changed)
                    onKeysChange(combined.joinToString("\n"))
                    lastInput = ""
                }
            },
            label = { Text(stringResource(R.string.setting_provider_page_new_api_key)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = {
                val input = lastInput.trim()
                if (input.isNotEmpty()) {
                    onKeysChange(splitKeys(keys + "\n" + input).joinToString("\n"))
                    lastInput = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.AddCircle, contentDescription = null)
            Text(stringResource(R.string.setting_provider_page_add_api_key))
        }
    }
}
