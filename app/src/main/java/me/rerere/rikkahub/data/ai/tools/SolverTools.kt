package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.SolveOutputParser
import me.rerere.rikkahub.data.ai.ocrSolveImages
import me.rerere.rikkahub.data.ai.subagent.SubAgentRequest
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStatus
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById

private const val TAG = "SolverTools"

/**
 * solve_question 工具：把「解题助手」以同步子代理的形式暴露给任意助手。
 *
 * 与 spawn_subagent（异步唤醒）不同：解题要求"返回结果后母代理交叉验证"，
 * 同步等待（runSynchronously）最直接；执行期间 UI 通过 observeTask(toolCallId)
 * 实时展示解题进度（子代理任务卡片），不会让用户面对无反馈的空窗。
 */
fun createQuestionSolverTools(
    subAgentRunner: SubAgentRunner,
    parentConversationId: kotlin.uuid.Uuid,
    context: Context,
    settings: Settings,
    providerManager: ProviderManager,
): List<Tool> {
    return listOf(
        Tool(
            name = "solve_question",
            description = """
                Dispatch the problem to a specialized tutor sub-agent that solves subject problems
                (math, physics, chemistry, biology, English, etc.) step by step and returns a full
                worked solution plus a refined, exam-ready final answer.

                Use when the user's message contains a concrete academic problem to be solved — a
                homework/exam/textbook question, with or without an attached image. Call it even if
                you can solve the problem yourself: the sub-agent works the problem independently in
                its own context, letting you cross-verify each step before answering, which is the
                whole point of this tool.

                Use when:
                - "请解这道题/这道题怎么做/solve this problem" with any concrete question
                - an image of a worksheet/exam/textbook problem is attached
                - the user asks you to check or verify an answer to a concrete problem

                Avoid: general concept explanations ("what is Newton's second law"), casual chat,
                or problems the user explicitly wants you to solve WITHOUT verification.

                The sub-agent cannot see this conversation. When the image is attached via
                `image_paths`, you do NOT need to transcribe the problem: the sub-agent reads the
                image itself (it may be a stronger reader than you). Put into `question` only the
                context you have: which problem to solve if the image holds several, any text you
                can read, and the subject if known. A full transcription in `question` is required
                only when you cannot attach the image.
                After receiving the result, cross-verify it against your own analysis step by step,
                point out any discrepancy, and give the user your final judgment — do not relay the
                result verbatim without checking. If you have no vision and cannot independently
                verify image-based work, relay the sub-agent's steps faithfully instead of rewriting
                them from your own (possibly wrong) reading of the image.
            """.trimIndent(),
            // 行为强引导（随 system 注入）：解题类消息必须先派发解题子代理再作答。
            // 根因：模型默认倾向"自己会做就不调工具"，且行为层提示词有"简单事自己做"的反派发倾向，
            // 不显式豁免会导致 solve_question 调用率极低。
            systemPrompt = { _, _ ->
                """
                ## solve_question Usage Policy
                - When the user's message contains a concrete problem/exercise/question to solve or
                  verify, you MUST call `solve_question` FIRST, before giving any full solution.
                  Do NOT write out your own complete solution without calling it — the independent
                  solve result is required for cross-verification.
                - This policy overrides the general "do simple things yourself" guidance: for
                  problems, calling `solve_question` IS the simple and expected path.
                - You may (and should) form your own preliminary analysis before or while waiting,
                  but your final answer must reconcile your analysis with the sub-agent's result:
                  agree → answer confidently; disagree → point out the divergent step and justify
                  your conclusion.
                - Cross-verification caveat: you can only reconcile steps you can independently
                  verify. If the problem is image-based and you have no vision (or the image is the
                  only source of truth), do NOT override the sub-agent's result based on your own
                  reading of the image — relay its steps faithfully, clearly mark the parts you
                  could not verify yourself, and let the user decide.
                """.trimIndent()
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("question", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "The problem statement in text form. When images are attached via " +
                                    "image_paths, this does NOT need to be a full transcription — " +
                                    "the sub-agent reads the image itself. Provide only the context " +
                                    "you have: which problem to solve if the image contains several " +
                                    "(e.g. \"the 2nd question\"), any text you can read, and the " +
                                    "subject if known (e.g. \"[physics] ...\"). A full transcription " +
                                    "is required only when you cannot attach the image."
                            )
                        })
                        put("image_paths", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put(
                                "description",
                                "Optional: local file paths of the problem image(s) taken from the " +
                                    "user messages in this conversation (file:// paths). The sub-agent " +
                                    "supports vision when the configured solve model does. If an image " +
                                    "contains several problems and the user did not point to one, " +
                                    "keep the crop to the intended problem when possible."
                            )
                        })
                        put("subject", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional subject hint, e.g. math/physics/chemistry/biology/english."
                            )
                        })
                    },
                    required = listOf("question"),
                )
            },
            injectToolCallId = true,
            execute = { args ->
                executeSolveQuestion(
                    args = args as? JsonObject,
                    subAgentRunner = subAgentRunner,
                    parentConversationId = parentConversationId,
                    context = context,
                    settings = settings,
                    providerManager = providerManager,
                )
            },
        ),
    )
}

private suspend fun executeSolveQuestion(
    args: JsonObject?,
    subAgentRunner: SubAgentRunner,
    parentConversationId: kotlin.uuid.Uuid,
    context: Context,
    settings: Settings,
    providerManager: ProviderManager,
): List<UIMessagePart> {
    if (args == null) {
        return listOf(UIMessagePart.Text("{\"error\":\"Invalid solve_question arguments\"}"))
    }
    val question = args["question"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return listOf(UIMessagePart.Text("{\"error\":\"'question' is required\"}"))
    val subject = args["subject"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val requestedImages = args["image_paths"]?.let { element ->
        runCatching {
            element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    } ?: emptyList()

    // 安全校验：模型传来的图片路径必须位于应用私有目录内（canonicalPath 前缀比对），
    // 防止伪造路径读任意文件。非法路径剔除并在结果中附警告。
    val filesDirCanonical = context.filesDir.canonicalPath
    val (images, rejectedImages) = requestedImages.partition { path ->
        runCatching {
            File(path).canonicalPath.startsWith(filesDirCanonical)
        }.getOrDefault(false)
    }

    // 图片但解题模型（或回落链上的默认模型）无视觉能力 → OCR 降级为文本
    var ocrText: String? = null
    var effectiveImages = images
    if (images.isNotEmpty()) {
        val solveModel = if (settings.solveModelId.toString().isNotBlank()) {
            settings.providers.findModelById(settings.solveModelId)
        } else null
        val visionAvailable = solveModel?.inputModalities?.contains(Modality.IMAGE) == true
        if (!visionAvailable) {
            ocrText = runCatching {
                ocrSolveImages(providerManager, settings, images)
                    .joinToString(separator = "\n\n")
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                Log.w(TAG, "solve_question OCR degradation failed: ${it.message}")
            }.getOrNull()
            if (ocrText == null) {
                return listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "Problem images provided but the solve model has no vision " +
                                "capability and OCR is unavailable. Ask the user to configure a " +
                                "vision-capable solve model or an OCR model in settings.")
                        }.toString()
                    )
                )
            }
            effectiveImages = emptyList()
        }
    }

    val task = buildString {
        append(question)
        subject?.let { append("\n\nSubject: $it") }
        ocrText?.let {
            append("\n\n<image_file_ocr>\n$it\n</image_file_ocr>")
            append("\n(The image_file_ocr content is OCR text extracted from the attached problem images.)")
        }
        // 输出语言跟随用户系统语言（solver 提示词按 Language 行裁决）
        val locale = java.util.Locale.getDefault()
        append("\n\nLanguage: ${locale.displayName} (${locale.toLanguageTag()})")
    }

    val toolCallId = args["__toolCallId"]?.jsonPrimitive?.contentOrNull
        ?: kotlin.uuid.Uuid.random().toString()

    val request = SubAgentRequest(
        agentId = "solver",
        task = task,
        modelId = settings.solveModelId.toString().takeIf { it.isNotBlank() },
        images = effectiveImages,
    )
    val result = runCatching {
        subAgentRunner.runSynchronously(request, parentConversationId, taskId = toolCallId)
    }.getOrElse {
        if (it is kotlinx.coroutines.CancellationException) throw it
        return listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "failed")
                    put("error", it.message ?: "unknown error")
                }.toString()
            )
        )
    }

    return when (result.status) {
        SubAgentStatus.SUCCEEDED -> {
            val solution = result.result
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString("\n") { it.text }
                .ifBlank { result.resultSummary ?: "" }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", "solved")
                        put("taskId", result.taskId)
                        put("solution", solution)
                        if (rejectedImages.isNotEmpty()) {
                            put(
                                "warning",
                                "Ignored invalid image paths (outside app-private storage): $rejectedImages"
                            )
                        }
                    }.toString()
                )
            )
        }

        SubAgentStatus.CANCELLED -> throw kotlinx.coroutines.CancellationException("solve_question cancelled")

        else -> listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", result.status.name.lowercase())
                    put("taskId", result.taskId)
                    result.error?.let { put("error", it) }
                    // 部分产物兜底：超时/失败时尽量带回已有输出，供母代理参考
                    result.streamText.takeIf { it.isNotBlank() }
                        ?.let { put("partialOutput", it.take(4000)) }
                }.toString()
            )
        )
    }
}
