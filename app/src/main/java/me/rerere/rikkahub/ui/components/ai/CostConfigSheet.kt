package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.cost.CostCalculator
import me.rerere.rikkahub.data.ai.cost.CostCurrency
import me.rerere.rikkahub.data.ai.cost.ModelPricingConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import org.koin.compose.koinInject

/**
 * 费用配置弹窗：编辑当前会话模型的定价（输入/输出/缓存输入单价 + 倍率），
 * 切换显示货币（USD / RMB）。改动全局生效（影响所有使用该模型 id 的会话）。
 */
@Composable
fun CostConfigSheet(
    settings: Settings,
    currentModelId: String?,
    onDismiss: () -> Unit,
) {
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val hapticController = rememberHaptic()

    // 预填当前模型定价：用户覆盖优先（含人民币字段），无覆盖则用内置预置价
    val existing = remember(currentModelId) {
        CostCalculator.resolve(currentModelId, settings.modelPricingOverrides)
    }

    var currency by remember { mutableStateOf(settings.costCurrency) }
    var rateStr by remember { mutableStateOf(settings.costUsdCnyRate.toString()) }

    fun effectiveRate(text: String): Double = (text.toDoubleOrNull() ?: 7.2).coerceAtLeast(1.0)

    // 价格输入框随货币动态重置为对应货币的预设单价（人民币无官方价时按汇率换算）。
    // remember key = (currency, existing)：切货币即重新取预设值；编辑中的输入不被打断。
    fun seedPrice(price: Double?): String = price?.toString() ?: "0"
    var inputStr by remember(currency, existing) {
        mutableStateOf(seedPrice(CostCalculator.unitPrices(currentModelId, settings.modelPricingOverrides, currency, effectiveRate(rateStr))?.first))
    }
    var outputStr by remember(currency, existing) {
        mutableStateOf(seedPrice(CostCalculator.unitPrices(currentModelId, settings.modelPricingOverrides, currency, effectiveRate(rateStr))?.second))
    }
    var cachedStr by remember(currency, existing) {
        mutableStateOf(seedPrice(CostCalculator.unitPrices(currentModelId, settings.modelPricingOverrides, currency, effectiveRate(rateStr))?.third))
    }
    var timeAware by remember(currentModelId) { mutableStateOf(existing?.timeAware ?: false) }
    var multiplierStr by remember { mutableStateOf(existing?.multiplier?.toString() ?: "1") }

    fun parsePositive(text: String): Double = (text.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "费用配置",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = "当前模型：${currentModelId ?: "未知"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 货币切换
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "显示货币",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                SingleChoiceSegmentedButtonRow {
                    CostCurrency.entries.forEachIndexed { index, c ->
                        SegmentedButton(
                            selected = currency == c,
                            onClick = { hapticController.perform(HapticFeedbackType.KeyboardTap); currency = c },
                            shape = SegmentedButtonDefaults.itemShape(index, CostCurrency.entries.size),
                        ) {
                            Text(if (c == CostCurrency.USD) "USD $" else "RMB ¥")
                        }
                    }
                }
            }

            if (currency == CostCurrency.RMB) {
                OutlinedTextField(
                    value = rateStr,
                    onValueChange = { rateStr = it },
                    label = { Text("美元→人民币汇率（1 USD = ? CNY）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider()

            if (currentModelId != null) {
                val unitLabel = if (currency == CostCurrency.USD) "USD / 1M tokens" else "RMB ¥ / 1M tokens"
                if (currentModelId.contains("deepseek", ignoreCase = true)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "按高峰/空闲自动计价（高峰 = 空闲 × 2）",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = timeAware,
                            onCheckedChange = { timeAware = it },
                        )
                    }
                }
                val inputLabel = if (timeAware) "空闲输入价（$unitLabel）" else "输入价（$unitLabel）"
                val outputLabel = if (timeAware) "空闲输出价（$unitLabel）" else "输出价（$unitLabel）"
                val cachedLabel = if (timeAware) "空闲缓存输入价（$unitLabel）" else "缓存输入价（$unitLabel）"
                PriceField(inputLabel, inputStr) { inputStr = it }
                PriceField(outputLabel, outputStr) { outputStr = it }
                PriceField(cachedLabel, cachedStr) { cachedStr = it }
                PriceField("费用倍率（代理上浮/打折，默认 1.0）", multiplierStr) { multiplierStr = it }
                if (timeAware) {
                    Text(
                        text = "高峰时段为北京时间 09:00-12:00、14:00-18:00，单价按空闲 × 2 计算。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "仅影响使用该模型 id 的所有会话，全局永久生效；人民币价无官方公布值时按汇率换算。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "未检测到当前模型，只能修改货币与汇率。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    onDismiss()
                }) {
                    Text("取消")
                }
                FilledTonalButton(onClick = {
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    val rate = (rateStr.toDoubleOrNull() ?: 7.2).coerceAtLeast(1.0)
                    val multiplier = (multiplierStr.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.01)
                    // 双货币保存：当前货币字段存编辑值，另一货币字段保留原覆盖/预置值，互不覆盖
                    val newOverride = currentModelId?.let { modelId ->
                        val base = existing
                        val editingCny = currency == CostCurrency.RMB
                        ModelPricingConfig(
                            modelId = modelId,
                            inputPriceUsd = if (editingCny) (base?.inputPriceUsd ?: 0.0) else parsePositive(inputStr),
                            outputPriceUsd = if (editingCny) (base?.outputPriceUsd ?: 0.0) else parsePositive(outputStr),
                            cachedInputPriceUsd = if (editingCny) (base?.cachedInputPriceUsd ?: 0.0) else parsePositive(cachedStr),
                            inputPriceCny = if (editingCny) parsePositive(inputStr) else (base?.inputPriceCny ?: 0.0),
                            outputPriceCny = if (editingCny) parsePositive(outputStr) else (base?.outputPriceCny ?: 0.0),
                            cachedInputPriceCny = if (editingCny) parsePositive(cachedStr) else (base?.cachedInputPriceCny ?: 0.0),
                            multiplier = multiplier,
                            timeAware = timeAware,
                        )
                    }
                    // 先完成写盘再关弹窗：rememberCoroutineScope 随弹窗移除而取消，
                    // 若 onDismiss 先行，写盘协程会在 dataStore.edit 挂起中途被杀，配置未落盘、重装即丢。
                    scope.launch {
                        settingsStore.update { s ->
                            s.copy(
                                costCurrency = currency,
                                costUsdCnyRate = rate,
                                modelPricingOverrides = if (newOverride != null) {
                                    s.modelPricingOverrides.filterNot { it.modelId == newOverride.modelId } + newOverride
                                } else {
                                    s.modelPricingOverrides
                                },
                            )
                        }
                        onDismiss()
                    }
                }) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun PriceField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
