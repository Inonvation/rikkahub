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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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

/**
 * Parsed representation of a single question from the ask_user tool arguments.
 */
data class AskUserQuestion(
    val id: String,
    val question: String,
    val rationale: String = "",
    val options: List<String> = emptyList(),
    val selectionType: String = "text",
    val placeholder: String = "",
    val required: Boolean = true,
)

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

    // Start on the first unanswered question
    var selectedIndex by remember(questions) {
        mutableIntStateOf(questions.indexOfFirst { !isQuestionAnswered(it, answers, multiAnswers) }.coerceAtLeast(0))
    }

    /** Explicitly advance to next unanswered question (no auto-advance loop). */
    fun advance() {
        // Only advance if the current question is actually answered now
        if (!isQuestionAnswered(questions[selectedIndex], answers, multiAnswers)) return
        val nextUnanswered = ((selectedIndex + 1) until questions.size)
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

            // ── Tabs — TabRow, evenly distributed filling full width ──
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
                            hapticController.perform(HapticFeedbackType.KeyboardTap)
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

            // ── Current question ──
            val q = questions[selectedIndex]

            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Question text
                Text(
                    text = q.question,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )

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
                        onSelected = { advance() },
                    )
                    "multi" -> MultiSelectInput(
                        question = q,
                        selected = multiAnswers[q.id] ?: emptySet(),
                        onToggle = { option ->
                            val cur = (multiAnswers[q.id] ?: emptySet()).toMutableSet()
                            if (cur.contains(option)) cur.remove(option) else cur.add(option)
                            multiAnswers[q.id] = cur
                            advance()
                        },
                    )
                    "confirmation" -> ConfirmationInput(
                        answer = answers[q.id] ?: "",
                        onAnswerChange = { answers[q.id] = it },
                        onSelected = { advance() },
                    )
                    else -> TextQuestionInput(
                        question = q,
                        answer = answers[q.id] ?: "",
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
            }

            // ── Footer ──
            val unansweredCount = questions.count { !isQuestionAnswered(it, answers, multiAnswers) }

            if (unansweredCount > 0) {
                Text(
                    text = "还有 ${unansweredCount} 个问题未填",
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
                        hapticController.perform(HapticFeedbackType.KeyboardTap)
                        onAnswerChange(if (answer == option) "" else option)
                    },
                    label = { Text(option, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }

    OutlinedTextField(
        value = answer,
        onValueChange = onAnswerChange,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        placeholder = question.placeholder.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        singleLine = false,
        minLines = 1,
        maxLines = 3,
    )
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
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
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
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
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
                    hapticController.perform(HapticFeedbackType.KeyboardTap)
                    onAnswerChange(if (answer == label) "" else label)
                    onSelected()
                },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── "Other" custom-answer helpers ──────────────────────────────────────────────

private val CUSTOM_SUFFIX = "::custom"

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
    return if (custom.isNotBlank()) {
        if (primary.isNotBlank()) "$primary\n(补充: $custom)" else custom
    } else {
        primary
    }
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
