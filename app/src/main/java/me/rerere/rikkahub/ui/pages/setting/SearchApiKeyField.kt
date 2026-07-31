package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalNavController

/**
 * 搜索服务的 API Key 编辑组件。
 * 单个 key 时为普通密码输入框；开启「多 Key」开关后显示入口卡片，跳转到多 Key 管理页。
 *
 * @param serviceId 搜索服务的 id
 * @param apiKey 当前 key 值
 * @param multipleKeys 是否开启多 key 模式
 * @param onApiKeyChange 修改 key 值
 * @param onMultipleKeysChange 修改多 key 开关
 */
@Composable
internal fun SearchApiKeyField(
    serviceId: String,
    apiKey: String,
    multipleKeys: Boolean,
    onApiKeyChange: (String) -> Unit,
    onMultipleKeysChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = LocalNavController.current
    FormItem(
        label = { Text(stringResource(R.string.search_detail_api_key)) },
        tail = {
            Switch(
                checked = multipleKeys,
                onCheckedChange = onMultipleKeysChange
            )
        }
    ) {
        if (multipleKeys) {
            MultiKeyEntryCard(
                apiKey = apiKey,
                onClick = {
                    navController.navigate(
                        Screen.SettingMultiKeyManage("search", serviceId)
                    )
                }
            )
        } else {
            var keyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(if (keyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                    }
                }
            )
        }
    }
}
