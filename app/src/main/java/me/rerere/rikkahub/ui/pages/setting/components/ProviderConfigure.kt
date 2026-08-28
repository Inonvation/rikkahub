package me.rerere.rikkahub.ui.pages.setting.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.rikkahub.data.ai.openai.OpenAICodexAuthService
import me.rerere.rikkahub.data.ai.openai.OpenAICodexDeviceCode
import me.rerere.rikkahub.data.ai.openai.parseCodexCredentialImport
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.compose.koinInject
import kotlin.reflect.KClass

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: (provider: ProviderSetting) -> Unit,
) {
    val hapticController = rememberHaptic()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        if (!provider.builtIn) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ProviderSetting.Types.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ProviderSetting.Types.size
                        ),
                        label = { Text(type.simpleName ?: "") },
                        selected = provider::class == type,
                        onClick = {
                            hapticController.lightTap()
                            onEdit(provider.convertTo(type))
                        }
                    )
                }
            }
        }

        when (provider) {
            is ProviderSetting.OpenAI -> ProviderConfigureOpenAI(provider, onEdit)
            is ProviderSetting.Google -> ProviderConfigureGoogle(provider, onEdit)
            is ProviderSetting.Claude -> ProviderConfigureClaude(provider, onEdit)
        }
    }
}

fun ProviderSetting.convertTo(type: KClass<out ProviderSetting>): ProviderSetting {
    if (this::class == type) return this

    val apiKey = when (this) {
        is ProviderSetting.OpenAI -> this.apiKey
        is ProviderSetting.Google -> this.apiKey
        is ProviderSetting.Claude -> this.apiKey
    }
    val sourceBaseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
    }
    val targetDefaultBaseUrl = when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI().baseUrl
        ProviderSetting.Google::class -> ProviderSetting.Google().baseUrl
        ProviderSetting.Claude::class -> ProviderSetting.Claude().baseUrl
        else -> error("Unsupported provider type: $type")
    }
    val convertedBaseUrl = sourceBaseUrl.convertToTargetBaseUrl(targetDefaultBaseUrl)

    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl,
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl,
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id, enabled = this.enabled, name = this.name, models = this.models,
            balanceOption = this.balanceOption, builtIn = this.builtIn,
            description = this.description, shortDescription = this.shortDescription,
            apiKey = apiKey, baseUrl = convertedBaseUrl,
        )
        else -> error("Unsupported provider type: $type")
    }
}

/**
 * ChatGPT 订阅（Codex / "GPT 登录"）资格：仅官方 OpenAI 端点（chatgpt.com /
 * api.openai.com）或已处于订阅态/已有凭据的供应商可用。第三方兼容端点
 * （DeepSeek、MIMO 等预设供应商）不提供该认证方式，UI 上隐藏对应选项。
 */
internal fun ProviderSetting.OpenAI.supportsChatGptSubscription(): Boolean {
    if (authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION || codexCredentials != null) return true
    val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return false
    return host == OPENAI_CODEX_HOST || host == OPENAI_OFFICIAL_HOST
}

internal fun ProviderSetting.defaultBaseUrlForReset(): String {
    // ChatGPT 订阅（Codex）重置回官方 Codex 端点，而不是 API Key 默认端点。
    if (this is ProviderSetting.OpenAI && authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) {
        return OPENAI_CODEX_BASE_URL
    }
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == id }
    if (defaultProvider != null) {
        when (this) {
            is ProviderSetting.OpenAI -> if (defaultProvider is ProviderSetting.OpenAI) return defaultProvider.baseUrl
            is ProviderSetting.Google -> if (defaultProvider is ProviderSetting.Google) return defaultProvider.baseUrl
            is ProviderSetting.Claude -> if (defaultProvider is ProviderSetting.Claude) return defaultProvider.baseUrl
        }
    }
    return when (this) {
        is ProviderSetting.OpenAI -> ProviderSetting.OpenAI().baseUrl
        is ProviderSetting.Google -> ProviderSetting.Google().baseUrl
        is ProviderSetting.Claude -> ProviderSetting.Claude().baseUrl
    }
}

internal fun ProviderSetting.resetBaseUrlToDefault(): ProviderSetting {
    val defaultBaseUrl = defaultBaseUrlForReset()
    return when (this) {
        is ProviderSetting.OpenAI -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Google -> this.copy(baseUrl = defaultBaseUrl)
        is ProviderSetting.Claude -> this.copy(baseUrl = defaultBaseUrl)
    }
}

internal fun ProviderSetting.isUsingDefaultBaseUrl(): Boolean {
    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
    }
    return baseUrl == defaultBaseUrlForReset()
}

/** 名称留空时回退到默认名称（按类型）。 */
internal fun ProviderSetting.defaultProviderName(): String = when (this) {
    is ProviderSetting.OpenAI -> "OpenAI"
    is ProviderSetting.Google -> "Google"
    is ProviderSetting.Claude -> "Claude"
}

/** 名称为空时补默认名称。 */
internal fun ProviderSetting.withDefaultNameIfBlank(): ProviderSetting = copyProvider(
    name = name.ifBlank { defaultProviderName() }
)

/** base URL 留空时回退到默认 base URL（按类型）。 */
internal fun ProviderSetting.withDefaultBaseUrlIfBlank(): ProviderSetting {
    val defaultBaseUrl = defaultBaseUrlForReset()
    return when (this) {
        is ProviderSetting.OpenAI -> copy(baseUrl = baseUrl.ifBlank { defaultBaseUrl })
        is ProviderSetting.Google -> copy(baseUrl = baseUrl.ifBlank { defaultBaseUrl })
        is ProviderSetting.Claude -> copy(baseUrl = baseUrl.ifBlank { defaultBaseUrl })
    }
}

private fun String.convertToTargetBaseUrl(targetDefaultBaseUrl: String): String {
    val sourceUrl = this.toHttpUrlOrNull() ?: return this
    val sourceHost = sourceUrl.host.lowercase()
    if (sourceHost in OFFICIAL_PROVIDER_HOSTS) return targetDefaultBaseUrl
    val targetUrl = targetDefaultBaseUrl.toHttpUrlOrNull() ?: return this
    val convertedPath = sourceUrl.encodedPath.convertToTargetPath(targetUrl.encodedPath)
    return sourceUrl.newBuilder().encodedPath(convertedPath).build().toString()
}

private fun String.convertToTargetPath(targetPath: String): String {
    val source = this.normalizePath()
    val target = targetPath.normalizePath()
    val replaced = when {
        source.lowercase().endsWith(V1_BETA_SUFFIX) -> source.dropLast(V1_BETA_SUFFIX.length) + target
        source.lowercase().endsWith(V1_SUFFIX) -> source.dropLast(V1_SUFFIX.length) + target
        source.isBlank() -> target
        else -> source + target
    }
    return replaced.normalizePath()
}

private fun String.normalizePath(): String {
    val value = this.trim()
    if (value.isEmpty() || value == "/") return ""
    val path = if (value.startsWith("/")) value else "/$value"
    return path.trimEnd('/')
}

private fun String.isValidBaseUrl(): Boolean = this.toHttpUrlOrNull() != null

private const val OPENAI_OFFICIAL_HOST = "api.openai.com"
private const val OPENAI_CODEX_HOST = "chatgpt.com"
private const val GOOGLE_OFFICIAL_HOST = "generativelanguage.googleapis.com"
private const val CLAUDE_OFFICIAL_HOST = "api.anthropic.com"
private const val V1_SUFFIX = "/v1"
private const val V1_BETA_SUFFIX = "/v1beta"
private val OFFICIAL_PROVIDER_HOSTS = setOf(
    OPENAI_OFFICIAL_HOST,
    OPENAI_CODEX_HOST,
    GOOGLE_OFFICIAL_HOST,
    CLAUDE_OFFICIAL_HOST
)

@Composable
private fun ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit,
) {
    val toaster = LocalToaster.current
    val hapticController = rememberHaptic()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val codexAuthService = koinInject<OpenAICodexAuthService>()
    var authJob by remember(provider.id) { mutableStateOf<Job?>(null) }
    var deviceCode by remember(provider.id) { mutableStateOf<OpenAICodexDeviceCode?>(null) }
    var authError by remember(provider.id) { mutableStateOf<String?>(null) }
    var signingIn by remember(provider.id) { mutableStateOf(false) }
    var importDialogVisible by remember(provider.id) { mutableStateOf(false) }
    var importText by remember(provider.id) { mutableStateOf("") }
    var importError by remember(provider.id) { mutableStateOf<String?>(null) }
    val signedInText = stringResource(R.string.setting_provider_page_signed_in)
    val signOutText = stringResource(R.string.setting_provider_page_sign_out)
    val selectedAuthType = provider.authType
    // ChatGPT 订阅（GPT 登录）仅对官方 OpenAI 端点有意义：订阅态请求固定走
    // https://chatgpt.com/backend-api/codex（见 ai 模块 effectiveBaseUrl），第三方
    // 兼容端点（DeepSeek、MIMO 等预设供应商）无法使用。非官方端点只保留 API Key
    // 一种认证方式并隐藏选择行，避免误导；已处于订阅态/已有凭据的供应商仍显示
    // 选择行（允许退出/续期，且 baseUrl 显示为官方 Codex 端点）。
    val authOptions = if (provider.supportsChatGptSubscription()) {
        OpenAIAuthType.entries
    } else {
        listOf(OpenAIAuthType.API_KEY)
    }

    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        placeholder = { Text(stringResource(R.string.setting_provider_page_name_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (authOptions.size > 1) {
        Text(stringResource(R.string.setting_provider_page_auth_method))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            authOptions.forEachIndexed { index, authType ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = authOptions.size,
                    ),
                    label = {
                        Text(
                            when (authType) {
                                OpenAIAuthType.API_KEY -> stringResource(R.string.setting_provider_page_api_key)
                                OpenAIAuthType.CHATGPT_SUBSCRIPTION -> stringResource(R.string.setting_provider_page_chatgpt_subscription)
                            }
                        )
                    },
                    selected = provider.authType == authType,
                    onClick = {
                        hapticController.lightTap()
                        authJob?.cancel()
                        authError = null
                        deviceCode = null
                        // 切换认证方式不改写 baseUrl：订阅模式下请求统一走官方 Codex 端点
                        // （ai 模块 effectiveBaseUrl），切回 API Key 时原 baseUrl 原样保留。
                        onEdit(
                            provider.copy(
                                authType = authType,
                                useResponseApi = authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION || provider.useResponseApi,
                            )
                        )
                    },
                )
            }
        }
    }

    when (selectedAuthType) {
        OpenAIAuthType.API_KEY -> {
            var keyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = provider.apiKey,
                onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
                label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            hapticController.lightTap()
                            keyVisible = !keyVisible
                        }
                    ) {
                        Icon(if (keyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                    }
                },
            )
        }

        OpenAIAuthType.CHATGPT_SUBSCRIPTION -> {
            Text(
                text = stringResource(R.string.setting_provider_page_chatgpt_subscription_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val credentials = provider.codexCredentials
            if (credentials != null) {
                val accountLabel = listOfNotNull(credentials.email, credentials.planType)
                    .joinToString(" · ")
                    .ifBlank { stringResource(R.string.setting_provider_page_signed_in) }
                Text(
                    text = accountLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (credentials.refreshToken.isBlank()) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_no_refresh_token_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = !signingIn,
                    onClick = {
                        authJob?.cancel()
                        authJob = scope.launch {
                            signingIn = true
                            authError = null
                            try {
                                val credentials = codexAuthService.signIn(provider.id) { code ->
                                    deviceCode = code
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(
                                            ClipData.newPlainText("OpenAI Codex device code", code.userCode)
                                        )
                                    CustomTabsOAuthAuthorizationLauncher.launch(context.applicationContext, code.verificationUrl)
                                }
                                deviceCode = null
                                onEdit(
                                    provider.copy(
                                        authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
                                        codexCredentials = credentials,
                                        useResponseApi = true,
                                    )
                                )
                                toaster.show(signedInText, type = ToastType.Success)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                authError = error.message ?: error.javaClass.simpleName
                            } finally {
                                signingIn = false
                                authJob = null
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (signingIn) R.string.setting_provider_page_signing_in
                            else R.string.setting_provider_page_sign_in_chatgpt
                        )
                    )
                }
                OutlinedButton(
                    enabled = !signingIn,
                    onClick = {
                        importText = ""
                        importError = null
                        importDialogVisible = true
                    },
                ) {
                    Text(stringResource(R.string.setting_provider_page_import_credentials))
                }
                if (credentials != null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                codexAuthService.signOut(provider.id)
                                onEdit(provider.copy(codexCredentials = null))
                                toaster.show(signOutText, type = ToastType.Info)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.setting_provider_page_sign_out))
                    }
                }
            }
            authError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    OutlinedTextField(
        value = if (selectedAuthType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) {
            OPENAI_CODEX_BASE_URL
        } else {
            provider.baseUrl
        },
        onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        placeholder = { Text(provider.defaultBaseUrlForReset()) },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
        enabled = selectedAuthType == OpenAIAuthType.API_KEY,
    )

    if (selectedAuthType == OpenAIAuthType.API_KEY) {
        OutlinedTextField(
            value = provider.chatCompletionsPath,
            onValueChange = { onEdit(provider.copy(chatCompletionsPath = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_path)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Embedding / Rerank 路径属于进阶项，默认折叠
    var expandAdvanced by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.setting_provider_page_more_settings),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                hapticController.lightTap()
                expandAdvanced = !expandAdvanced
            }
        ) {
            Icon(
                imageVector = if (expandAdvanced) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = stringResource(R.string.setting_provider_page_more_settings),
            )
        }
    }
    AnimatedVisibility(
        visible = expandAdvanced,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = provider.embeddingsPath,
                onValueChange = { onEdit(provider.copy(embeddingsPath = it.trim())) },
                label = { Text("Embedding 路径") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = provider.rerankPath,
                onValueChange = { onEdit(provider.copy(rerankPath = it.trim())) },
                label = { Text("Rerank 路径") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = {
                hapticController.tap()
                onEdit(provider.copy(enabled = it))
            }
        )
    }

    val responseAPIWarning = stringResource(R.string.setting_provider_page_response_api_warning)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_response_api))
        Switch(
            checked = provider.useResponseApi,
            enabled = selectedAuthType == OpenAIAuthType.API_KEY,
            onCheckedChange = {
                hapticController.tap()
                onEdit(provider.copy(useResponseApi = it))
                if (it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                    toaster.show(message = responseAPIWarning, type = ToastType.Warning)
                }
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_include_history_reasoning))
        Switch(
            checked = provider.includeHistoryReasoning,
            onCheckedChange = {
                hapticController.tap()
                onEdit(provider.copy(includeHistoryReasoning = it))
            }
        )
    }

    deviceCode?.let { code ->
        AlertDialog(
            onDismissRequest = {
                authJob?.cancel()
                deviceCode = null
            },
            title = { Text(stringResource(R.string.setting_provider_page_device_code_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.setting_provider_page_device_code_desc))
                    Text(
                        text = code.userCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = JetbrainsMono,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.setting_provider_page_device_code_verify_url),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 可长按复制网址，方便在电脑等其它设备浏览器中打开验证页
                        SelectionContainer {
                            Text(
                                text = code.verificationUrl,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = JetbrainsMono,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.setting_provider_page_device_code_other_device_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        authJob?.cancel()
                        deviceCode = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        CustomTabsOAuthAuthorizationLauncher.launch(context.applicationContext, code.verificationUrl)
                    }
                ) {
                    Text(stringResource(R.string.setting_provider_page_open_browser))
                }
            },
        )
    }

    if (importDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                importDialogVisible = false
                importError = null
            },
            title = { Text(stringResource(R.string.setting_provider_page_import_credentials)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_import_credentials_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = {
                            importText = it
                            importError = null
                        },
                        label = { Text(stringResource(R.string.setting_provider_page_import_credentials_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 12,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
                    )
                    importError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        importDialogVisible = false
                        importError = null
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                val parsed = parseCodexCredentialImport(importText)
                                val credentials = codexAuthService.importCredentials(
                                    providerId = provider.id,
                                    accessToken = parsed.accessToken,
                                    refreshToken = parsed.refreshToken,
                                    accountId = parsed.accountId,
                                    email = parsed.email,
                                    planType = parsed.planType,
                                )
                                importDialogVisible = false
                                importError = null
                                onEdit(
                                    provider.copy(
                                        authType = OpenAIAuthType.CHATGPT_SUBSCRIPTION,
                                        codexCredentials = credentials,
                                        useResponseApi = true,
                                    )
                                )
                                toaster.show(signedInText, type = ToastType.Success)
                            } catch (error: kotlinx.coroutines.CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                importError = error.message ?: error.javaClass.simpleName
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.setting_provider_page_import_credentials_confirm))
                }
            },
        )
    }
}

@Composable
private fun ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit,
) {

    val hapticController = rememberHaptic()
    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        placeholder = { Text(stringResource(R.string.setting_provider_page_name_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
    )

    var keyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(
                onClick = {
                    hapticController.lightTap()
                    keyVisible = !keyVisible
                }
            ) {
                Icon(if (keyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
            }
        },
    )

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
        placeholder = { Text(provider.defaultBaseUrlForReset()) },
        modifier = Modifier.fillMaxWidth(),
        isError = provider.baseUrl.isNotBlank() && !provider.baseUrl.isValidBaseUrl(),
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = {
                hapticController.tap()
                onEdit(provider.copy(enabled = it))
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_claude_prompt_caching))
        Switch(
            checked = provider.promptCaching,
            onCheckedChange = {
                hapticController.tap()
                onEdit(provider.copy(promptCaching = it))
            }
        )
    }

    if (provider.promptCaching) {
        Text(stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ClaudePromptCacheTtl.entries.forEachIndexed { index, ttl ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ClaudePromptCacheTtl.entries.size
                    ),
                    label = {
                        Text(
                            when (ttl) {
                                ClaudePromptCacheTtl.FIVE_MINUTES -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_5m)
                                ClaudePromptCacheTtl.ONE_HOUR -> stringResource(R.string.setting_provider_page_claude_prompt_cache_ttl_1h)
                            }
                        )
                    },
                    selected = provider.promptCacheTtl == ttl,
                    onClick = {
                        hapticController.lightTap()
                        onEdit(provider.copy(promptCacheTtl = ttl))
                    }
                )
            }
        }
    }
}

@Composable
private fun ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting.Google) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val hapticController = rememberHaptic()
    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val content = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.readText()
                ?: return@rememberLauncherForActivityResult
            val json = Json.parseToJsonElement(content).jsonObject
            onEdit(
                provider.copy(
                    projectId = json["project_id"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.projectId,
                    serviceAccountEmail = json["client_email"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.serviceAccountEmail,
                    privateKey = json["private_key"]?.jsonPrimitive?.contentOrNull?.ifEmpty { null } ?: provider.privateKey,
                )
            )
            toaster.show("Service account imported", type = ToastType.Success)
        } catch (e: Exception) {
            toaster.show("Failed to import: ${e.message}", type = ToastType.Error)
        }
    }

    provider.description()

    OutlinedTextField(
        value = provider.name,
        onValueChange = { onEdit(provider.copy(name = it.trim())) },
        label = { Text(stringResource(R.string.setting_provider_page_name)) },
        placeholder = { Text(stringResource(R.string.setting_provider_page_name_placeholder)) },
        modifier = Modifier.fillMaxWidth(),
    )

    if (!(provider.vertexAI && provider.useServiceAccount)) {
        var keyVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_key)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(
                    onClick = {
                        hapticController.lightTap()
                        keyVisible = !keyVisible
                    }
                ) {
                    Icon(if (keyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            },
        )
    }

    if (!provider.vertexAI) {
        OutlinedTextField(
            value = provider.baseUrl,
            onValueChange = { onEdit(provider.copy(baseUrl = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_api_base_url)) },
            placeholder = { Text(provider.defaultBaseUrlForReset()) },
            modifier = Modifier.fillMaxWidth(),
            isError = provider.baseUrl.isNotBlank() && (
                !provider.baseUrl.isValidBaseUrl() || !provider.baseUrl.endsWith("/v1beta")
                ),
            supportingText = if (provider.baseUrl.isNotBlank() && !provider.baseUrl.endsWith("/v1beta")) {
                { Text("The base URL usually ends with `/v1beta`") }
            } else null,
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_enable))
        Switch(
            checked = provider.enabled,
            onCheckedChange = {
                hapticController.tap()
                onEdit(provider.copy(enabled = it))
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.setting_provider_page_vertex_ai))
        Switch(
            checked = provider.vertexAI,
            onCheckedChange = {
                hapticController.tap()
                onEdit(provider.copy(vertexAI = it))
            }
        )
    }

    if (provider.vertexAI) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.setting_provider_page_use_service_account))
            Switch(
                checked = provider.useServiceAccount,
                onCheckedChange = {
                    hapticController.tap()
                    onEdit(provider.copy(useServiceAccount = it))
                }
            )
        }
    }

    if (provider.vertexAI && provider.useServiceAccount) {
        OutlinedButton(
            onClick = {
                hapticController.tap()
                serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*"))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.setting_provider_page_import_service_account_json))
        }

        OutlinedTextField(
            value = provider.serviceAccountEmail,
            onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_service_account_email)) },
            modifier = Modifier.fillMaxWidth(),
        )

        var privateKeyVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.privateKey,
            onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_private_key)) },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 6,
            minLines = 3,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetbrainsMono),
            visualTransformation = if (privateKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(
                    onClick = {
                        hapticController.lightTap()
                        privateKeyVisible = !privateKeyVisible
                    }
                ) {
                    Icon(if (privateKeyVisible) HugeIcons.ViewOff else HugeIcons.View, contentDescription = null)
                }
            },
        )

        OutlinedTextField(
            value = provider.location,
            onValueChange = { onEdit(provider.copy(location = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_location)) },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = provider.projectId,
            onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
            label = { Text(stringResource(R.string.setting_provider_page_project_id)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
