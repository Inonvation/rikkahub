package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.splitApiKeys
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.setting.components.MultiKeyEditor
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.search.SearchServiceOptions
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/**
 * 多 Key 管理页。
 * 通过 source 区分编辑对象：
 *  - "provider"：AI 模型供应商（ProviderSetting）
 *  - "search"：搜索服务（SearchServiceOptions）
 *
 * 本地编辑 + 底部「保存」按钮，点保存才写 Store。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiKeyManagePage(
    source: String,
    id: Uuid,
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current

    when (source) {
        "provider" -> {
            val provider = settings.providers.find { it.id == id } ?: return
            val apiKey = when (provider) {
                is ProviderSetting.OpenAI -> provider.apiKey
                is ProviderSetting.Google -> provider.apiKey
                is ProviderSetting.Claude -> provider.apiKey
            }
            MultiKeyManageScaffold(
                title = stringResource(R.string.multi_key_manage_title),
                apiKey = apiKey,
                onSave = { newKeys ->
                    val cleaned = splitApiKeys(newKeys).joinToString("\n")
                    val updated = when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(apiKey = cleaned)
                        is ProviderSetting.Google -> provider.copy(apiKey = cleaned)
                        is ProviderSetting.Claude -> provider.copy(apiKey = cleaned)
                    }
                    vm.updateSettings(
                        settings.copy(
                            providers = settings.providers.map {
                                if (it.id == id) updated else it
                            }
                        )
                    )
                    toaster.show(context.getString(R.string.setting_provider_page_save_success), type = ToastType.Success)
                }
            )
        }

        "search" -> {
            val service = settings.searchServices.find { it.id == id } ?: return
            val apiKey = when (service) {
                is SearchServiceOptions.TavilyOptions -> service.apiKey
                is SearchServiceOptions.ExaOptions -> service.apiKey
                is SearchServiceOptions.ZhipuOptions -> service.apiKey
                is SearchServiceOptions.LinkUpOptions -> service.apiKey
                is SearchServiceOptions.BraveOptions -> service.apiKey
                is SearchServiceOptions.MetasoOptions -> service.apiKey
                is SearchServiceOptions.OllamaOptions -> service.apiKey
                is SearchServiceOptions.PerplexityOptions -> service.apiKey
                is SearchServiceOptions.FirecrawlOptions -> service.apiKey
                is SearchServiceOptions.JinaOptions -> service.apiKey
                is SearchServiceOptions.BochaOptions -> service.apiKey
                is SearchServiceOptions.RikkaHubOptions -> service.apiKey
                is SearchServiceOptions.GrokOptions -> service.apiKey
                is SearchServiceOptions.TinyfishOptions -> service.apiKey
                is SearchServiceOptions.SerperOptions -> service.apiKey
                else -> ""
            }
            MultiKeyManageScaffold(
                title = stringResource(R.string.multi_key_manage_title),
                apiKey = apiKey,
                onSave = { newKeys ->
                    val cleaned = splitApiKeys(newKeys).joinToString("\n")
                    val updated = when (service) {
                        is SearchServiceOptions.TavilyOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.ExaOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.ZhipuOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.LinkUpOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.BraveOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.MetasoOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.OllamaOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.PerplexityOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.FirecrawlOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.JinaOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.BochaOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.RikkaHubOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.GrokOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.TinyfishOptions -> service.copy(apiKey = cleaned)
                        is SearchServiceOptions.SerperOptions -> service.copy(apiKey = cleaned)
                        else -> service
                    }
                    vm.updateSettings(
                        settings.copy(
                            searchServices = settings.searchServices.map {
                                if (it.id == id) updated else it
                            }
                        )
                    )
                    toaster.show(context.getString(R.string.setting_provider_page_save_success), type = ToastType.Success)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MultiKeyManageScaffold(
    title: String,
    apiKey: String,
    onSave: (String) -> Unit,
) {
    var keysText by rememberSaveable { mutableStateOf(apiKey) }
    val dirty = keysText != apiKey

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
                title = {
                    Text(title)
                }
            )
        },
        bottomBar = {
            Surface {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (dirty) {
                        Text(
                            text = stringResource(R.string.multi_key_manage_unsaved),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Button(
                        onClick = { onSave(keysText) }
                    ) {
                        Text(stringResource(R.string.setting_provider_page_save))
                    }
                }
            }
        },
        containerColor = CustomColors.topBarColors.containerColor
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.multi_key_manage_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                MultiKeyEditor(
                    keys = keysText,
                    onKeysChange = { keysText = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
