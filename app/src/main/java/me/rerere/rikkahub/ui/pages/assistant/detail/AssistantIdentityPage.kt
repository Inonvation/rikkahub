package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.toFixed
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.roundToInt
import me.rerere.rikkahub.data.model.Tag as DataTag

/**
 * 助手设置 · ① 身份与外观。
 * 承载：名称 / 头像 / 标签 / 使用助手头像 / 聊天外观（渐变背景、背景图、不透明度）。
 */
@Composable
fun AssistantIdentityPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("身份与外观")
                },
                navigationIcon = {
                    BackButton()
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantIdentityContent(
            innerPadding = innerPadding,
            assistant = assistant,
            tags = tags,
            onUpdate = { vm.update(it) },
            vm = vm
        )
    }
}

@Composable
private fun AssistantIdentityContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    tags: List<DataTag>,
    onUpdate: (Assistant) -> Unit,
    vm: AssistantDetailVM
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = innerPadding + PaddingValues(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UIAvatar(
                    value = assistant.avatar,
                    name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    onUpdate = { avatar ->
                        onUpdate(
                            assistant.copy(
                                avatar = avatar
                            )
                        )
                    },
                    modifier = Modifier
                        .size(80.dp)
                        .heroAnimation("assistant_${assistant.id}")
                )
            }
        }

        item {
            Card(
                colors = CustomColors.cardColorsOnSurfaceContainer
            ) {
                FormItem(
                    label = {
                        Text(stringResource(R.string.assistant_page_name))
                    },
                    modifier = Modifier.padding(8.dp),
                ) {
                    OutlinedTextField(
                        value = assistant.name,
                        onValueChange = {
                            onUpdate(
                                assistant.copy(
                                    name = it
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider()

                FormItem(
                    label = {
                        Text(stringResource(R.string.assistant_page_tags))
                    },
                    modifier = Modifier.padding(8.dp),
                ) {
                    TagsInput(
                        value = assistant.tags,
                        tags = tags,
                        onValueChange = { tagIds, tagList ->
                            vm.updateTags(tagIds, tagList)
                        },
                    )
                }

                HorizontalDivider()

                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_use_assistant_avatar))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_use_assistant_avatar_desc))
                    },
                    tail = {
                        Switch(
                            checked = assistant.useAssistantAvatar,
                            onCheckedChange = {
                                onUpdate(
                                    assistant.copy(
                                        useAssistantAvatar = it
                                    )
                                )
                            }
                        )
                    }
                )
            }
        }

        item {
            Card(
                colors = CustomColors.cardColorsOnSurfaceContainer
            ) {
                FormItem(
                    modifier = Modifier.padding(8.dp),
                    label = {
                        Text(stringResource(R.string.assistant_page_gradient_background))
                    },
                    description = {
                        Text(stringResource(R.string.assistant_page_gradient_background_desc))
                    },
                    tail = {
                        Switch(
                            checked = assistant.useGradientBackground,
                            onCheckedChange = {
                                onUpdate(
                                    assistant.copy(
                                        useGradientBackground = it
                                    )
                                )
                            }
                        )
                    }
                )

                if (!assistant.useGradientBackground) {
                    HorizontalDivider()

                    BackgroundPicker(
                        modifier = Modifier.padding(8.dp),
                        background = assistant.background,
                        backgroundOpacity = assistant.backgroundOpacity,
                        onUpdate = { background ->
                            onUpdate(
                                assistant.copy(
                                    background = background
                                )
                            )
                        }
                    )
                }

                if (!assistant.useGradientBackground && assistant.background != null) {
                    val backgroundOpacity = assistant.backgroundOpacity.coerceIn(0f, 1f)
                    HorizontalDivider()
                    FormItem(
                        modifier = Modifier.padding(8.dp),
                        label = {
                            Text(stringResource(R.string.assistant_page_background_opacity))
                        },
                        description = {
                            Text(stringResource(R.string.assistant_page_background_opacity_desc))
                        },
                    ) {
                        Slider(
                            value = backgroundOpacity,
                            onValueChange = {
                                onUpdate(
                                    assistant.copy(
                                        backgroundOpacity = it.toFixed(2).toFloatOrNull()?.coerceIn(0f, 1f) ?: 1.0f
                                    )
                                )
                            },
                            valueRange = 0f..1f,
                            steps = 19,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = stringResource(
                                R.string.assistant_page_background_opacity_value,
                                (backgroundOpacity * 100).roundToInt()
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}
