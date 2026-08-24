package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.OutlinedNumberInput
import me.rerere.rikkahub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkahub.ui.hooks.rememberHaptic

private const val DEFAULT_CONTEXT_TOKEN_LIMIT = 128_000

internal fun resolveContextTokenLimit(
    modelContextTokenLimit: Int?,
    assistantContextTokenLimit: Int,
): Int = modelContextTokenLimit?.takeIf { it > 0 }
    ?: assistantContextTokenLimit.takeIf { it > 0 }
    ?: DEFAULT_CONTEXT_TOKEN_LIMIT

internal fun autoCompressShouldTrigger(
    totalTokens: Int,
    contextTokenLimit: Int,
    thresholdPercent: Int,
    enabled: Boolean,
): Boolean {
    if (!enabled || totalTokens <= 0 || contextTokenLimit <= 0) return false
    return totalTokens.toFloat() / contextTokenLimit * 100f >= thresholdPercent.coerceIn(1, 100)
}

internal fun autoCompressResetThreshold(thresholdPercent: Int): Int =
    (thresholdPercent - 10).coerceAtLeast(1)

@Composable
fun CompressContextDialog(
    contextTokenLimit: Int,
    modelContextTokenLimit: Int? = null,
    autoCompressEnabled: Boolean,
    autoCompressThreshold: Int,
    onAutoCompressEnabledChange: (Boolean) -> Unit,
    onAutoCompressThresholdChange: (Int) -> Unit,
    onSaveContextTokenLimit: (Int) -> Unit,
    onDismiss: () -> Unit,
    onCompress: (additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int) -> Job
) {
    var contextLimit by remember {
        mutableIntStateOf(
            resolveContextTokenLimit(modelContextTokenLimit, contextTokenLimit)
        )
    }
    var additionalPrompt by remember { mutableStateOf("") }
    var selectedTokens by remember { mutableIntStateOf(0) }
    var keepRecentMessages by remember { mutableIntStateOf(0) }
    val hapticController = rememberHaptic()
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val isLoading = currentJob?.isActive == true

    // Monitor job completion
    LaunchedEffect(currentJob) {
        currentJob?.join()
        if (currentJob?.isCompleted == true && currentJob?.isCancelled == false) {
            onDismiss()
        }
        currentJob = null
    }

    AlertDialog(
        onDismissRequest = {
            if (!isLoading) {
                onDismiss()
            }
        },
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                modifier = Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isLoading) {
                    // Loading state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                } else {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_page_compress_auto_enable),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = autoCompressEnabled,
                            onCheckedChange = onAutoCompressEnabledChange,
                        )
                    }

                    if (autoCompressEnabled) {
                        OutlinedNumberInput(
                            value = autoCompressThreshold,
                            onValueChange = { onAutoCompressThresholdChange(it.coerceIn(1, 100)) },
                            label = stringResource(R.string.chat_page_compress_auto_threshold),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Context token limit input (for top bar indicator)
                    OutlinedNumberInput(
                        value = contextLimit,
                        onValueChange = { contextLimit = it },
                        label = stringResource(R.string.chat_page_compress_context_token_limit),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Keep recent messages input
                    OutlinedNumberInput(
                        value = keepRecentMessages,
                        onValueChange = { keepRecentMessages = it },
                        label = stringResource(R.string.chat_page_compress_keep_recent),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Target token size input
                    OutlinedNumberInput(
                        value = selectedTokens,
                        onValueChange = { selectedTokens = it },
                        label = stringResource(R.string.chat_page_compress_target_tokens),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Additional context input
                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

                    // Warning text
                    Text(
                        text = stringResource(R.string.chat_page_compress_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (isLoading) {
                TextButton(onClick = {
                    hapticController.lightTap()
                    currentJob?.cancel()
                    currentJob = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = {
                    hapticController.lightTap()
                    // 兜底：删除或非法时恢复默认 128k
                    val effectiveLimit = contextLimit.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKEN_LIMIT
                    onSaveContextTokenLimit(effectiveLimit)
                    // 只有目标 Token 和保留消息数都大于 0 时才执行压缩
                    if (selectedTokens > 0 && keepRecentMessages > 0) {
                        currentJob = onCompress(additionalPrompt, selectedTokens, keepRecentMessages)
                    } else {
                        onDismiss()
                    }
                }) {
                    Text(stringResource(R.string.chat_page_compress_save))
                }
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = {
                    hapticController.lightTap()
                    onDismiss()
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
