package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Github
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.github.DeviceFlowPhase
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingScaffold
import me.rerere.rikkahub.utils.openUrl
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingGitHubPage() {
    val vm: GitHubAuthVM = koinViewModel()
    val state by vm.authState.collectAsStateWithLifecycle()
    val rateLimit by vm.rateLimit.collectAsStateWithLifecycle()
    val tokenInjection by vm.tokenInjectionEnabled.collectAsStateWithLifecycle()
    val lastAccount by vm.lastAccount.collectAsStateWithLifecycle()
    val deviceRetrying by vm.deviceRetrying.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showPatDialog by remember { mutableStateOf(false) }
    var showUnbindConfirm by remember { mutableStateOf(false) }

    SettingScaffold(title = "GitHub") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("account") {
                IosGroup(title = "账号") {
                    val account = state.account
                    if (account == null) {
                        if (vm.isConfigured) {
                            item(
                                onClick = { vm.signIn() },
                                leadingContent = { Icon(HugeIcons.Github, null) },
                                headlineContent = { Text("使用 GitHub 登录") },
                                supportingContent = {
                                    Text(
                                        lastAccount?.let { "上次绑定 ${it.login}，重新授权即可恢复" }
                                            ?: "浏览器授权后自动绑定（Device Flow）"
                                    )
                                },
                                trailingContent = {
                                    Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                },
                            )
                        } else {
                            item(
                                headlineContent = { Text("使用 GitHub 登录") },
                                supportingContent = {
                                    Text("未配置 OAuth client_id（local.properties: GITHUB_CLIENT_ID），可先使用下方 token 方式")
                                },
                            )
                        }
                        item(
                            onClick = { showPatDialog = true },
                            headlineContent = { Text("使用 Personal Access Token") },
                            supportingContent = { Text("粘贴 token 手动绑定（也支持 fine-grained token）") },
                        )
                    } else {
                        item(
                            leadingContent = {
                                if (account.avatarUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = account.avatarUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(32.dp),
                                    )
                                } else {
                                    Icon(HugeIcons.Github, null)
                                }
                            },
                            headlineContent = { Text(account.login) },
                            supportingContent = {
                                val scopeText = if (account.scopes.isNotEmpty()) {
                                    "scope: ${account.scopes.joinToString(", ")}"
                                } else {
                                    "已绑定"
                                }
                                Text(if (state.invalid) "$scopeText（已失效，请重新绑定）" else scopeText)
                            },
                        )
                        if (state.invalid) {
                            item(
                                onClick = { vm.signIn() },
                                headlineContent = { Text("重新绑定") },
                            )
                        }
                        if (vm.isConfigured) {
                            item(
                                onClick = {
                                    context.openUrl(
                                        "https://github.com/settings/connections/applications/${BuildConfig.GITHUB_CLIENT_ID}"
                                    )
                                },
                                headlineContent = { Text("管理授权") },
                                supportingContent = { Text("在 GitHub 上查看/撤销本应用的授权") },
                            )
                        }
                        item(
                            onClick = { showUnbindConfirm = true },
                            headlineContent = { Text("解除绑定") },
                        )
                    }
                }
            }

            item("workspace") {
                IosGroup(title = "工作区") {
                    item(
                        onClick = { vm.setTokenInjection(!tokenInjection) },
                        headlineContent = { Text("向工作区 shell 注入 GITHUB_TOKEN") },
                        supportingContent = {
                            Text(
                                "开启后工作区内 curl/git/gh 可用该凭据（api.github.com 提额至 5000 次/小时、私有仓库可克隆）。" +
                                    "命令输出中的 token 会被自动脱敏"
                            )
                        },
                        trailingContent = {
                            Switch(checked = tokenInjection, onCheckedChange = { vm.setTokenInjection(it) })
                        },
                    )
                }
            }

            item("ratelimit") {
                val snapshot = rateLimit
                // 30s 心跳刷新重置倒计时
                var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(30_000)
                        nowMs = System.currentTimeMillis()
                    }
                }
                IosGroup(title = "API 配额") {
                    if (snapshot != null) {
                        val minutesLeft = ((snapshot.resetEpochSec * 1000 - nowMs) / 60000L).coerceAtLeast(0)
                        item(
                            headlineContent = { Text("${snapshot.remaining} / ${snapshot.limit}") },
                            supportingContent = {
                                Text(
                                    "剩余 / 总量 · " +
                                        if (minutesLeft > 0) "约 $minutesLeft 分钟后重置" else "配额已重置"
                                )
                            },
                        )
                    } else {
                        item(
                            headlineContent = { Text("未绑定") },
                            supportingContent = { Text("未认证配额 60 次/小时/IP；绑定后 5000 次/小时") },
                        )
                    }
                }
            }
        }
    }

    if (showPatDialog) {
        PatBindDialog(
            vm = vm,
            onDismiss = { showPatDialog = false },
        )
    }

    if (showUnbindConfirm) {
        AlertDialog(
            onDismissRequest = { showUnbindConfirm = false },
            title = { Text("解除绑定 GitHub 账号？") },
            text = {
                Text(
                    if (tokenInjection) {
                        "将删除本地凭据，工作区 GitHub 注入将失效；GitHub 侧授权需另行撤销"
                    } else {
                        "将删除本地凭据，GitHub 侧授权需另行撤销"
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.unbind()
                        showUnbindConfirm = false
                    },
                ) { Text("解除绑定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUnbindConfirm = false }) { Text("取消") }
            },
        )
    }

    when (val phase = vm.devicePhase.collectAsStateWithLifecycle().value) {
        is DeviceFlowPhase.CodeReady -> {
            DeviceFlowDialog(
                phase = phase,
                retrying = deviceRetrying,
                onDismiss = { vm.dismissDeviceDialog() },
            )
        }

        is DeviceFlowPhase.Failed -> {
            AlertDialog(
                onDismissRequest = { vm.dismissDeviceDialog() },
                title = { Text("GitHub 授权失败") },
                text = { Text(phase.reason) },
                confirmButton = {
                    TextButton(onClick = { vm.dismissDeviceDialog() }) { Text("关闭") }
                },
            )
        }

        else -> {}
    }
}

@Composable
private fun DeviceFlowDialog(
    phase: DeviceFlowPhase.CodeReady,
    retrying: Boolean,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在浏览器中完成授权") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. 复制下方代码；2. 打开 github.com/login/device；3. 粘贴并授权")
                Text(
                    text = phase.userCode,
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "授权码 15 分钟内有效，应用会自动检测授权结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (retrying) {
                    Text(
                        text = "网络不稳，自动重试中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(phase.userCode))
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(phase.verificationUri)))
            }) { Text("复制并打开浏览器") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PatBindDialog(
    vm: GitHubAuthVM,
    onDismiss: () -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var binding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴 Personal Access Token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("经典 token 需包含 repo scope（读私仓）；仅提升配额可用零 scope token")
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ghp_... / github_pat_...") },
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    binding = true
                    vm.bindWithPat(token) { err ->
                        binding = false
                        if (err == null) onDismiss() else error = err
                    }
                },
                enabled = token.isNotBlank() && !binding,
            ) { Text(if (binding) "绑定中…" else "绑定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
