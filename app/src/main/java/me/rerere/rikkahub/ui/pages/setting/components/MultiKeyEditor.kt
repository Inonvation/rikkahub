package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import me.rerere.ai.util.splitApiKeys
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AddCircle
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import kotlin.uuid.Uuid

/**
 * 一行 key 的编辑状态。
 * 每行用不可变 [id] 作为稳定身份（编辑时 value 变、id 不变，焦点不丢），
 * 删除按 id 删，其余行不受影响。
 */
internal data class KeyRow(val id: Uuid, val value: String)

/**
 * 多个 key 的列表编辑组件。
 *
 * - 每个输入框只能输入一个 key（单行，过滤分隔符），不再支持"粘贴多 key 自动拆分"
 * - 清空输入框只是把该行置空，不会删除行；空白行在保存时被过滤
 * - 重复 key 标红 + 底部提示；添加重复 key 时 toast 且不清空输入
 * - 每行有稳定身份，编辑/删除不丢焦点
 */
@Composable
internal fun MultiKeyEditor(
    keys: String,
    onKeysChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rows by remember { mutableStateOf(seedRows(keys)) }
    var lastInput by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    val toaster = LocalToaster.current
    val context = LocalContext.current

    val duplicateValues = rows.map { it.value.trim() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                    imageVector = if (keyVisible) HugeIcons.ViewOff else HugeIcons.View,
                    contentDescription = stringResource(
                        if (keyVisible) R.string.multi_key_hide else R.string.multi_key_show
                    )
                )
            }
        }

        rows.forEach { row ->
            key(row.id) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = row.value,
                        onValueChange = { changed ->
                            // 单行单 key：只允许普通字符输入，过滤掉分隔符，避免触发整表拆分
                            val sanitized = changed.replace(SPLIT_KEY_REGEX, "")
                            val updated = rows.map {
                                if (it.id == row.id) it.copy(value = sanitized) else it
                            }
                            rows = updated
                            onKeysChange(updated.joinToString("\n") { it.value })
                        },
                        label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                        isError = row.value.trim() in duplicateValues,
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val updated = rows.filterNot { it.id == row.id }
                            rows = updated
                            onKeysChange(updated.joinToString("\n") { it.value })
                        }
                    ) {
                        Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.multi_key_delete))
                    }
                }
            }
        }

        OutlinedTextField(
            value = lastInput,
            onValueChange = { changed ->
                lastInput = changed.replace(SPLIT_KEY_REGEX, "")
            },
            label = { Text(stringResource(R.string.setting_provider_page_new_api_key)) },
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedButton(
            onClick = {
                val input = lastInput.trim()
                if (input.isEmpty()) return@OutlinedButton
                if (rows.any { it.value.trim() == input }) {
                    toaster.show(context.getString(R.string.multi_key_duplicate), type = ToastType.Warning)
                    return@OutlinedButton
                }
                val updated = rows + KeyRow(Uuid.random(), input)
                rows = updated
                onKeysChange(updated.joinToString("\n") { it.value })
                lastInput = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(HugeIcons.AddCircle, contentDescription = null)
            Text(stringResource(R.string.setting_provider_page_add_api_key))
        }

        if (duplicateValues.isNotEmpty()) {
            Text(
                text = stringResource(R.string.multi_key_duplicate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private val SPLIT_KEY_REGEX = me.rerere.ai.util.SPLIT_KEY_REGEX

private fun seedRows(keys: String): List<KeyRow> {
    val split = splitApiKeys(keys)
    return if (split.isEmpty()) {
        listOf(KeyRow(Uuid.random(), ""))
    } else {
        split.map { KeyRow(Uuid.random(), it) }
    }
}
