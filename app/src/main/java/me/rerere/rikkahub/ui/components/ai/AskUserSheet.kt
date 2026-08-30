package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.rikkahub.ui.hooks.rememberHaptic

/** Short tab title: just the question index. */
private fun AskUserQuestion.tabTitle(index: Int): String = "Q${index + 1}"

/**
 * Half-screen question sheet.  Horizontal tabs across the top, one question at a time.
 * Every question type also shows a free-text "other" field below the primary input.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskUserSheet(
    title: String?,
    questions: List<AskUserQuestion>,
    answers: MutableMap<String, String>,
    multiAnswers: MutableMap<String, Set<String>>,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hapticController = rememberHaptic()

    // 模型未返回有效问题时渲染友好空态，避免 questions[0] 越界崩溃
    if (questions.isEmpty()) {
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
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title ?: "提问",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "模型未返回有效的问题，无法作答。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(HugeIcons.Cancel01, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("关闭")
                    }
                    TextButton(
                        onClick = {
                            // 空问题的死锁出口：把「问题无效」回传模型，让其修正参数重新提问
                            onSubmit(
                                buildJsonObject {
                                    put("answers", buildJsonObject { })
                                    put("error", "ask_user 未返回有效的问题列表，请检查参数后重新提问")
                                }.toString()
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(HugeIcons.Tick01, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("请模型重新提问")
                    }
                }
            }
        }
        return
    }

    // Start on the first unanswered question
    var selectedIndex by remember(questions) {
        mutableIntStateOf(questions.indexOfFirst { !isQuestionAnswered(it, answers, multiAnswers) }.coerceAtLeast(0))
    }

    /**
     * 回答完一个问题后自动跳到其后的第一个未回答问题。
     * 仅在「该问题确实已答」时前进；点击 tab 切换不会触发，保证用户可随时点任意问题修改。
     * 仅单选/确认题调用：多选逐项勾选、文本输入都是连续操作，中途自动跳转会打断
     * （文本题首字符跳转还会连焦点一起切到下一题，后续输入全部落错），由用户自行切换或提交。
     */
    fun advanceFrom(answeredIndex: Int) {
        if (!isQuestionAnswered(questions[answeredIndex], answers, multiAnswers)) return
        val nextUnanswered = ((answeredIndex + 1) until questions.size)
            .firstOrNull { i -> !isQuestionAnswered(questions[i], answers, multiAnswers) }
        if (nextUnanswered != null) {
            selectedIndex = nextUnanswered
        }
    }

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
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Title ──
            Text(
                text = title ?: "请回答以下问题",
                style = MaterialTheme.typography.titleMedium,
            )

            // ── Tabs — 多问题才显示 Tab 行，等宽铺满；单问题无需切换直接隐藏 ──
            if (questions.size > 1) {
                TabRow(
                    selectedTabIndex = selectedIndex,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Color.Transparent,
                ) {
                    questions.forEachIndexed { index, q ->
                        val isAnswered = isQuestionAnswered(q, answers, multiAnswers)
                        Tab(
                            selected = index == selectedIndex,
                            onClick = {
                                hapticController.lightTap()
                                selectedIndex = index
                            },
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    if (isAnswered) {
                                        Icon(
                                            HugeIcons.Tick02,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Text(
                                        text = q.tabTitle(index),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // ── Current question ──
            val q = questions[selectedIndex]

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Question text + 类型标记（多选/选填）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = q.question,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // required 默认 true 是常态，只把例外的选填题标出来，避免满屏星号噪音
                    if (!q.required) QuestionTag("选填")
                    if (q.selectionType == "multi") QuestionTag("多选")
                }

                // Rationale
                if (q.rationale.isNotBlank()) {
                    Text(
                        text = q.rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(4.dp))

                // ── Primary input by type ──
                when (q.selectionType) {
                    "single" -> SingleSelectInput(
                        question = q,
                        answer = answers[q.id] ?: "",
                        onAnswerChange = { answers[q.id] = it },
                        onSelected = { advanceFrom(selectedIndex) },
                    )
                    "multi" -> MultiSelectInput(
                        question = q,
                        selected = multiAnswers[q.id] ?: emptySet(),
                        onToggle = { option ->
                            val cur = (multiAnswers[q.id] ?: emptySet()).toMutableSet()
                            if (cur.contains(option)) cur.remove(option) else cur.add(option)
                            multiAnswers[q.id] = cur
                        },
                    )
                    "confirmation" -> ConfirmationInput(
                        answer = answers[q.id] ?: "",
                        onAnswerChange = { answers[q.id] = it },
                        onSelected = { advanceFrom(selectedIndex) },
                    )
                    else -> TextQuestionInput(
                        question = q,
                        answer = answers[q.id] ?: "",
                        // 文本题不自动跳转：首字符跳转会连焦点一起切到下一题，后续输入全部落错地方
                        onAnswerChange = { answers[q.id] = it },
                    )
                }

                // ── "Other" free-text input for non-text types ──
                if (q.selectionType != "text") {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "其他 / 自定义输入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = getCustomAnswer(q, answers, multiAnswers),
                        onValueChange = { setCustomAnswer(q, it, answers, multiAnswers) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 2,
                        placeholder = { Text("填写以上未列出的内容…") },
                    )
                }

                // ── Skip — 必答题不想答的显式出口；已答或选填题不出现（选填留空即可） ──
                val skipped = isQuestionSkipped(q, answers)
                if (q.required && (skipped || !isQuestionAnswered(q, answers, multiAnswers))) {
                    TextButton(
                        onClick = {
                            hapticController.lightTap()
                            val key = q.id + SKIPPED_SUFFIX
                            if (skipped) {
                                answers.remove(key)
                            } else {
                                answers[key] = "1"
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            text = if (skipped) "取消跳过" else "跳过本题",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            // ── Footer ──
            // 兑现工具 schema 的 required 承诺：必答问题作答或显式跳过后才能提交，选填留空不算未填
            val requiredUnanswered = questions.count {
                it.required && !isQuestionAnswered(it, answers, multiAnswers) && !isQuestionSkipped(it, answers)
            }

            if (requiredUnanswered > 0) {
                Text(
                    text = "还有 ${requiredUnanswered} 个必答问题未填",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.Cancel01, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("取消")
                }
                TextButton(
                    onClick = {
                        val payload = buildJsonObject {
                            put("answers", buildJsonObject {
                                questions.forEach { q ->
                                    put(q.id, JsonPrimitive(getFinalAnswer(q, answers, multiAnswers)))
                                }
                            })
                        }
                        onSubmit(payload.toString())
                    },
                    enabled = requiredUnanswered == 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(HugeIcons.Tick01, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("提交")
                }
            }
        }
    }
}

// ── Type-specific inputs ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextQuestionInput(question: AskUserQuestion, answer: String, onAnswerChange: (String) -> Unit) {
    val hapticController = rememberHaptic()

    if (question.options.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            question.options.forEach { option ->
                FilterChip(
                    selected = answer == option,
                    onClick = {
                        hapticController.lightTap()
                        onAnswerChange(if (answer == option) "" else option)
                    },
                    label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    OutlinedTextField(
        value = answer,
        onValueChange = onAnswerChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.bodySmall,
        placeholder = question.placeholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        singleLine = false,
        minLines = 1,
        maxLines = 3,
    )
    // 切到该问题时自动聚焦文本框（仅聚焦，不强制跳转/切换问题）
    LaunchedEffect(question.id) {
        focusRequester.requestFocus()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SingleSelectInput(
    question: AskUserQuestion,
    answer: String,
    onAnswerChange: (String) -> Unit,
    onSelected: () -> Unit,
) {
    val hapticController = rememberHaptic()

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        question.options.forEach { option ->
            FilterChip(
                selected = answer == option,
                onClick = {
                    hapticController.lightTap()
                    onAnswerChange(if (answer == option) "" else option)
                    onSelected()
                },
                label = { Text(option, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectInput(question: AskUserQuestion, selected: Set<String>, onToggle: (String) -> Unit) {
    val hapticController = rememberHaptic()

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        question.options.forEach { option ->
            FilterChip(
                selected = selected.contains(option),
                onClick = {
                    hapticController.lightTap()
                    onToggle(option)
                },
                label = { Text(option, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun ConfirmationInput(answer: String, onAnswerChange: (String) -> Unit, onSelected: () -> Unit) {
    val hapticController = rememberHaptic()

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("是" to true, "否" to false).forEach { (label, value) ->
            FilterChip(
                selected = answer == label,
                onClick = {
                    hapticController.lightTap()
                    onAnswerChange(if (answer == label) "" else label)
                    onSelected()
                },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Question tag badge ────────────────────────────────────────────────────────

/** 问题文本旁的小标记（多选/选填），tonal 底色圆角小块 */
@Composable
private fun QuestionTag(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── "Other" custom-answer helpers ──────────────────────────────────────────────

private val CUSTOM_SUFFIX = "::custom"

/** 跳过标记存储键后缀：复用 answers 草稿持久化（切页/重组后跳过状态不丢），不参与提交 payload */
private const val SKIPPED_SUFFIX = "::skipped"

private fun getCustomAnswer(
    q: AskUserQuestion,
    answers: Map<String, String>,
    multiAnswers: Map<String, Set<String>>,
): String {
    val key = q.id + CUSTOM_SUFFIX
    return answers[key] ?: ""
}

private fun setCustomAnswer(
    q: AskUserQuestion,
    value: String,
    answers: MutableMap<String, String>,
    multiAnswers: MutableMap<String, Set<String>>,
) {
    answers[q.id + CUSTOM_SUFFIX] = value
}

/** Build the final answer string: primary answer + optional custom text. */
private fun getFinalAnswer(
    q: AskUserQuestion,
    answers: Map<String, String>,
    multiAnswers: Map<String, Set<String>>,
): String {
    val primary = when (q.selectionType) {
        "multi" -> (multiAnswers[q.id] ?: emptySet()).joinToString(", ")
        else -> answers[q.id] ?: ""
    }
    val custom = getCustomAnswer(q, answers, multiAnswers)
    val direct = if (custom.isNotBlank()) {
        if (primary.isNotBlank()) "$primary\n(补充: $custom)" else custom
    } else {
        primary
    }
    if (direct.isNotBlank()) return direct
    // 必答题被显式跳过时回传明确语义，让模型知道用户拒绝作答而非漏答
    return if (isQuestionSkipped(q, answers)) "(已跳过)" else ""
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun isQuestionAnswered(
    q: AskUserQuestion,
    answers: Map<String, String>,
    multiAnswers: Map<String, Set<String>>,
): Boolean = when (q.selectionType) {
    "multi" -> (!multiAnswers[q.id].isNullOrEmpty() || getCustomAnswer(q, answers, multiAnswers).isNotBlank())
    else -> (!answers[q.id].isNullOrBlank() || getCustomAnswer(q, answers, multiAnswers).isNotBlank())
}

/** 是否已把该题显式标记为跳过（必答题不想答时的出口，与「已作答」互相独立） */
private fun isQuestionSkipped(q: AskUserQuestion, answers: Map<String, String>): Boolean =
    answers[q.id + SKIPPED_SUFFIX] == "1"
