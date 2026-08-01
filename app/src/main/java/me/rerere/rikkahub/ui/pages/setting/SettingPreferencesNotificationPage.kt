package me.rerere.rikkahub.ui.pages.setting

import android.os.Build
import androidx.compose.foundation.layout.padding
import me.rerere.rikkahub.ui.components.ui.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesNotificationPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val permissionState = rememberPermissionState(
        permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setOf(
            PermissionNotification
        ) else emptySet(),
    )
    PermissionManager(permissionState = permissionState)

    SettingListScaffold(
        title = stringResource(R.string.setting_page_preferences_notification),
    ) {
        item {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated)) },
                    supportingContent = { Text(stringResource(R.string.setting_display_page_notification_message_generated_desc)) },
                    trailingContent = {
                        Switch(
                            checked = displaySetting.enableNotificationOnMessageGeneration,
                            onCheckedChange = {
                                if (it && !permissionState.allPermissionsGranted) {
                                    permissionState.requestPermissions()
                                }
                                updateDisplaySetting(displaySetting.copy(enableNotificationOnMessageGeneration = it))
                            }
                        )
                    },
                )
                if (displaySetting.enableNotificationOnMessageGeneration) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_display_page_live_update_notification)) },
                        supportingContent = { Text(stringResource(R.string.setting_display_page_live_update_notification_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableLiveUpdateNotification,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableLiveUpdateNotification = it))
                                }
                            )
                        },
                    )
                }
            }
        }
    }
}
