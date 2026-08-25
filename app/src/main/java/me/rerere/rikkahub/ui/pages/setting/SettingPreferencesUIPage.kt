package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Message02
import me.rerere.hugeicons.stroke.SlidersVertical
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.WebDesign01
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

/**
 * 界面偏好设置入口页：按分组聚合为二级入口，点击进入 [SettingDisplayGroupPage]。
 */
@Composable
fun SettingPreferencesUIPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    SettingListScaffold(
        title = stringResource(R.string.setting_page_preferences_ui),
        loading = settings.init,
    ) {
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                item(
                    onClick = { navController.navigate(Screen.SettingDisplayGroup("layout")) },
                    leadingContent = { Icon(HugeIcons.SlidersVertical, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_layout)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_layout_group_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingDisplayGroup("bubbles")) },
                    leadingContent = { Icon(HugeIcons.Message02, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_message_bubbles)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_bubbles_group_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingDisplayGroup("info")) },
                    leadingContent = { Icon(HugeIcons.InformationCircle, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_message_info)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_info_group_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingDisplayGroup("thinking")) },
                    leadingContent = { Icon(HugeIcons.Brain02, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_thinking)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_thinking_group_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingDisplayGroup("font")) },
                    leadingContent = { Icon(HugeIcons.Text, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_font)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_font_group_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingDisplayGroup("render")) },
                    leadingContent = { Icon(HugeIcons.WebDesign01, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_rendering)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_render_group_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingDisplayGroup("code")) },
                    leadingContent = { Icon(HugeIcons.Code, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_code_display_settings)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_code_group_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingDisplayGroup("tts")) },
                    leadingContent = { Icon(HugeIcons.VolumeHigh, null) },
                    headlineContent = { Text(stringResource(R.string.setting_page_tts_playback)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_tts_group_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingDisplayGroup("haptic")) },
                    leadingContent = { Icon(HugeIcons.Zap, null) },
                    headlineContent = { Text(stringResource(R.string.setting_display_page_haptic_group_title)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_haptic_group_desc)) },
                    trailingContent = { Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }
    }
}
