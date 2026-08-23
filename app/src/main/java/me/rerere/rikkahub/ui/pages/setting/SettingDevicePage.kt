package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.shizuku.ShizukuService
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingScaffold
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus

/**
 * 设备能力设置页：Shizuku 授权状态、安全设置入口。
 * 白名单与审批的详细配置在 Phase 2 接入。
 */
@Composable
fun SettingDevicePage() {
    val context = LocalContext.current
    var installed by remember { mutableStateOf(ShizukuService.isShizukuInstalled(context)) }
    var serviceRunning by remember { mutableStateOf(ShizukuService.isServiceRunning()) }
    var permissionGranted by remember { mutableStateOf(ShizukuService.hasPermission()) }

    DisposableEffect(Unit) {
        val listener = {
            installed = ShizukuService.isShizukuInstalled(context)
            serviceRunning = ShizukuService.isServiceRunning()
            permissionGranted = ShizukuService.hasPermission()
        }
        ShizukuService.addStateChangeListener(listener)
        onDispose { ShizukuService.removeStateChangeListener(listener) }
    }

    SettingScaffold(title = "设备能力") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                IosGroup(title = "Shizuku 状态") {
                    item(
                        leadingContent = {
                            Icon(
                                if (installed) HugeIcons.Zap else HugeIcons.Alert01,
                                null,
                                tint = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        },
                        headlineContent = { Text(if (installed) "Shizuku 已安装" else "Shizuku 未安装") },
                    )
                    item(
                        leadingContent = {
                            Icon(
                                if (serviceRunning) HugeIcons.Zap else HugeIcons.Alert01,
                                null,
                                tint = if (serviceRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        },
                        headlineContent = { Text(if (serviceRunning) "服务运行中" else "服务未运行") },
                    )
                    item(
                        leadingContent = {
                            Icon(
                                if (permissionGranted) HugeIcons.Zap else HugeIcons.Alert01,
                                null,
                                tint = if (permissionGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        },
                        headlineContent = { Text(if (permissionGranted) "已授予权限" else "未授予权限") },
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        when {
                            !installed -> context.openUrl("https://shizuku.rikka.app/")
                            !serviceRunning -> context.openUrl("https://shizuku.rikka.app/")
                            !permissionGranted -> ShizukuService.requestPermission()
                            else -> ShizukuService.refreshState()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        when {
                            !installed -> "去安装 Shizuku"
                            !serviceRunning -> "查看启动说明"
                            !permissionGranted -> "请求授权"
                            else -> "重新检测"
                        }
                    )
                }
            }
            item {
                IosGroup(title = "安全设置") {
                    item(
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        headlineContent = { Text("白名单管理") },
                        supportingContent = { Text("微信、QQ 及系统关键应用保护，后续版本开放") },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        leadingContent = { Icon(HugeIcons.Settings03, null) },
                        headlineContent = { Text("工具审批") },
                        supportingContent = { Text("每次询问 / 自动允许 / 禁止，后续版本开放") },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                }
            }
            item {
                IosGroup(title = "说明") {
                    item(
                        headlineContent = { Text("设备能力让 AI 可以冻结应用、分析存储、诊断手机。需要先在手机上启动 Shizuku 并授予 adb 权限，所有写操作默认会经过你的确认。") },
                    )
                }
            }
        }
    }
}