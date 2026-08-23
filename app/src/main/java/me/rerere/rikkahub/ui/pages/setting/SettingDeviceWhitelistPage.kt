package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.device.DeviceSafetyStore
import me.rerere.rikkahub.data.device.SafetyGuard
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingScaffold
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

/**
 * 白名单管理：默认保护（微信/QQ/支付宝）、用户自定义保护、系统硬保护。
 */
@Composable
fun SettingDeviceWhitelistPage() {
    val store: DeviceSafetyStore = koinInject()
    val safetyGuard: SafetyGuard = koinInject()
    val scope = rememberCoroutineScope()

    val userProtected by store.userProtected.collectAsStateWithLifecycle(initialValue = emptySet())
    val userUnprotected by store.userUnprotected.collectAsStateWithLifecycle(initialValue = emptySet())
    val hardProtected by produceState<Set<String>>(initialValue = emptySet()) {
        value = safetyGuard.hardProtectedSnapshot()
    }

    var newPackage by remember { mutableStateOf("") }

    SettingScaffold(title = "白名单管理") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                IosGroup(title = "默认保护") {
                    safetyGuard.defaultProtectedSnapshot().forEach { pkg ->
                        val isUnprotected = pkg in userUnprotected
                        item(
                            supportingContent = { Text(if (isUnprotected) "已移除保护，可冻结" else "受保护，不可冻结") },
                            headlineContent = { Text(pkg) },
                            trailingContent = {
                                TextButton(onClick = {
                                    scope.launch {
                                        if (isUnprotected) store.removeUnprotected(pkg) else store.addUnprotected(pkg)
                                    }
                                }) {
                                    Text(if (isUnprotected) "恢复保护" else "移除保护")
                                }
                            },
                        )
                    }
                }
            }
            item {
                IosGroup(title = "用户自定义保护") {
                    if (userProtected.isEmpty()) {
                        item(headlineContent = { Text("暂无自定义保护应用") })
                    }
                    userProtected.forEach { pkg ->
                        item(
                            headlineContent = { Text(pkg) },
                            trailingContent = {
                                TextButton(onClick = { scope.launch { store.removeProtected(pkg) } }) {
                                    Text("移除")
                                }
                            },
                        )
                    }
                }
            }
            item {
                IosGroup(title = "添加保护应用") {
                    item {
                        Column(modifier = Modifier.padding(12.dp)) {
                            OutlinedTextField(
                                value = newPackage,
                                onValueChange = { newPackage = it },
                                placeholder = { Text("输入应用包名，如 com.tencent.mm") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Button(
                                    onClick = {
                                        val pkg = newPackage.trim()
                                        if (pkg.isNotEmpty()) {
                                            scope.launch { store.addProtected(pkg) }
                                            newPackage = ""
                                        }
                                    }
                                ) {
                                    Text("添加")
                                }
                            }
                        }
                    }
                }
            }
            item {
                IosGroup(title = "系统硬保护（不可修改）") {
                    hardProtected.sorted().forEach { pkg ->
                        item(headlineContent = { Text(pkg) })
                    }
                }
            }
            item {
                IosGroup(title = "说明") {
                    item(
                        headlineContent = { Text("冻结应用前会先检查保护名单。系统硬保护（桌面、输入法、系统进程、本应用）任何情况下不可冻结；默认保护（微信/QQ/支付宝）默认不可冻结，可在此移除。") },
                    )
                }
            }
        }
    }
}