package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.nav.BackButton
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiKeyManagePage(
    source: String,
    id: Uuid,
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()

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
                onApiKeyChange = { newKeys ->
                    val updated = when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(apiKey = newKeys)
                        is ProviderSetting.Google -> provider.copy(apiKey = newKeys)
                        is ProviderSetting.Claude -> provider.copy(apiKey = newKeys)
                    }
                    vm.updateSettings(
                        settings.copy(
                            providers = settings.providers.map {
                                if (it.id == id) updated else it
                            }
                        )
                    )
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
                onApiKeyChange = { newKeys ->
                    val updated = when (service) {
                        is SearchServiceOptions.TavilyOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.ExaOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.ZhipuOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.LinkUpOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.BraveOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.MetasoOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.OllamaOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.PerplexityOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.FirecrawlOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.JinaOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.BochaOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.RikkaHubOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.GrokOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.TinyfishOptions -> service.copy(apiKey = newKeys)
                        is SearchServiceOptions.SerperOptions -> service.copy(apiKey = newKeys)
                        else -> service
                    }
                    vm.updateSettings(
                        settings.copy(
                            searchServices = settings.searchServices.map {
                                if (it.id == id) updated else it
                            }
                        )
                    )
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
    onApiKeyChange: (String) -> Unit,
) {
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
                    keys = apiKey,
                    onKeysChange = onApiKeyChange,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
