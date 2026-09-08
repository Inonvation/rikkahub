package me.rerere.rikkahub.ui.pages.solve

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.SolveResult
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.UNSET_MODEL_ID
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.db.dao.SolveHistoryDao
import me.rerere.rikkahub.data.db.entity.SolveRecordEntity
import org.json.JSONArray
import org.json.JSONObject
import me.rerere.rikkahub.service.SolveGenerationForegroundService
import me.rerere.rikkahub.utils.ImageUtils

private const val TAG = "SolveVM"

/** 历史记录上限：超过后裁剪最老记录（与翻译历史同策略） */
private const val HISTORY_LIMIT = 100

/** 裁剪图片的私有持久化目录（filesDir 相对路径），与历史记录生命周期绑定 */
private const val SOLVE_IMAGE_DIR = "solve_images"

/** 裁切落盘前的解码/重编码工作文件目录（cache，随用随删） */
private const val SOLVE_WORK_DIR = "solve_work"

/** 相机取景输出目录（cache）：pending 原图暂存地，与 FileProvider content:// uri 对应 */
private const val SOLVE_CAMERA_DIR = "solve_camera"

/** 旋转产物输出目录（cache）：与相机目录分离——旋转生成新文件时若与旧 pending 同目录，
 *  清目录语义会把新文件误删；独立目录 + cancel/confirm 时整体清理，闭环无残留 */
private const val SOLVE_ROTATE_DIR = "solve_rotate"

/** 解码上限：与 uCrop maxResultSize 4096 对齐，控制内存与 token 成本 */
private const val MAX_CROP_DIMENSION = 4096

/** JPEG 落盘质量：题图是解题输入，质量优先于体积（发送层另有压缩） */
private const val SOLVE_JPEG_QUALITY = 92

/** 裁切后最小边长（px）：框选过小视为误触，防止把脏点当题目 */
private const val MIN_CROP_EDGE = 8

/**
 * 孤儿图片清扫的延迟窗口（ms）：
 * 根因——历史删除带「撤销」入口（snackbar 短暂窗口）。若删记录时立刻物理删除题图，
 * 撤销只回插 DB 记录、文件已丢，历史行缩略图变空（imagePath 悬空）。
 * 改为删除记录后延迟清扫：窗口内撤销的记录重新进入引用集，清扫按「当前引用集」判断，
 * 幂等安全；窗口后仍无引用的文件才真正删除，也顺带回收超限裁剪（insertAndTrim）的孤儿。
 */
private const val ORPHAN_SWEEP_DELAY_MS = 10_000L

/**
 * 拍照解题页面的主状态机阶段：驱动整页布局。
 *
 * 根因：v1 用「内容非空」推断页面阶段（hasActivity），一旦引入拍照后、解题前的
 * 「框选确认」中间态就无法表达（此时 generating=false 且无任何输出内容）。
 * 显式状态机让每个阶段与一种全屏布局一一对应，交互闭环可读可测。
 */
sealed interface SolvePhase {
    /** 全屏相机取景（无题图时的主视图） */
    data object ViewFinder : SolvePhase

    /** 已拍到/选到原图，等待用户框选题目区域 */
    data object CropConfirm : SolvePhase

    /** 解题中（框选确认自动触发，或结果态点「重新解题」） */
    data object Solving : SolvePhase

    /** 有产出（含半成品）：题图摘要 + 题干/思考/解答/作答 + 底部操作 */
    data object Result : SolvePhase
}

/** 结果页内的一轮追问：用户问题 + 模型思考 + 模型回答（仅内存 + 行内快照，不建子表） */
data class FollowUpTurn(
    val question: String,
    val reasoning: String = "",
    val answer: String = "",
)

/**
 * 一次完整解题产出的不可变快照：供「重新解题失败回滚」与「上一版解答回看」共用。
 *
 * 根因：新一轮求解在 launchSolveInternal 开头就清空三段输出，若新请求失败，
 * 上一轮成功结果已被清掉、连题图一并被 resetToViewFinder 删除——用户重解一次
 * 可能弄丢可用答案。引入上一轮快照：失败时回滚展示；也作为「上一版解答」
 * 的只读数据源（会话内，不落库）。
 */
data class SolveSnapshot(
    val images: List<String>,
    val resultQuestion: String?,
    val reasoning: String,
    val process: String,
    val finalAnswer: String,
    val noteText: String,
    val reasoningStartAt: Long? = null,
    val reasoningEndAt: Long? = null,
    val followUps: List<FollowUpTurn> = emptyList(),
    val activeRecordId: String? = null,
)

/**
 * 拍照解题控制器。
 *
 * 为什么不是 ViewModel：解题是长任务，用户解题中途退出页面（pop 导航）后
 * 仍希望任务继续、再次进入能接上进度——ViewModel 随页面 entry 销毁会取消 job。
 * 以 Koin 单例 + AppScope 常驻：job 挂在 AppScope 上，页面只订阅状态；
 * 退出页面不取消生成，重进页面读到同一份状态继续展示。进程被杀则任务终止
 * （前台服务只防进程冻结），已完成结果已落库，不丢历史。
 */
class SolveVM(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val generationHandler: GenerationHandler,
    private val solveHistoryDao: SolveHistoryDao,
    private val appScope: CoroutineScope,
) {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(appScope, SharingStarted.Lazily, Settings.dummy())

    private val _phase = MutableStateFlow<SolvePhase>(SolvePhase.ViewFinder)
    val phase: StateFlow<SolvePhase> = _phase.asStateFlow()

    /** 待框选的原图（相机/相册输出），仅在 CropConfirm 阶段非空；确认后转 [images] 并清空 */
    private val _pendingCapture = MutableStateFlow<String?>(null)
    val pendingCapture: StateFlow<String?> = _pendingCapture.asStateFlow()

    /** 已框选并持久化到 filesDir/solve_images 的题图（file:// uri），解题与历史回填的输入源 */
    private val _images = MutableStateFlow<List<String>>(emptyList())
    val images: StateFlow<List<String>> = _images.asStateFlow()

    // 用户补充描述（可选；当前在框选确认底部输入）
    private val _noteText = MutableStateFlow("")
    val noteText: StateFlow<String> = _noteText.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    private val _reasoning = MutableStateFlow("")
    val reasoning: StateFlow<String> = _reasoning.asStateFlow()

    private val _process = MutableStateFlow("")
    val process: StateFlow<String> = _process.asStateFlow()

    private val _finalAnswer = MutableStateFlow("")
    val finalAnswer: StateFlow<String> = _finalAnswer.asStateFlow()

    /**
     * 思考起止时间（epoch ms）：startAt = 首个 reasoning chunk 到达时刻，
     * endAt = 首个 process/final chunk 到达（或任务结束）时刻。
     * UI 据此显示「思考了 n 秒」并在生成中实时计时，语义与聊天的
     * Reasoning.createdAt/finishedAt 一致。
     */
    private val _reasoningStartAt = MutableStateFlow<Long?>(null)
    val reasoningStartAt: StateFlow<Long?> = _reasoningStartAt.asStateFlow()

    private val _reasoningEndAt = MutableStateFlow<Long?>(null)
    val reasoningEndAt: StateFlow<Long?> = _reasoningEndAt.asStateFlow()

    /**
     * 题干卡文本（全路径有值，语义随来源不同）：
     * - vision 直送路径：模型 <problem_statement> 读题块（AI 识读的题面），流式解析到即上屏，
     *   用户可据此对照原图判断 AI 有没有识别错题目，错可立即停止；
     * - OCR 降级/纯文本路径：solveQuestion 回传的题干文本（OCR 结果 + 用户补充），
     *   输入侧权威，模型读题块只作为内部复述、不回填覆盖（避免 OCR 原文被模型改写降保真）。
     * 两个来源都展示在结果页题干卡，可修正后重新解题。
     */
    private val _resultQuestion = MutableStateFlow<String?>(null)
    val resultQuestion: StateFlow<String?> = _resultQuestion.asStateFlow()

    // 解题历史记录（新的在前）
    val records: StateFlow<List<SolveRecordEntity>> = solveHistoryDao.listAll()
        .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    val errorFlow = MutableSharedFlow<Throwable>()

    private var currentJob: Job? = null

    /** 当前生成对应的前台服务保活 id，任务结束 finally 兜底释放 */
    private var currentSolveId: String? = null

    // ---------- 结果页页内追问 ----------

    private val _followUps = MutableStateFlow<List<FollowUpTurn>>(emptyList())
    val followUps: StateFlow<List<FollowUpTurn>> = _followUps.asStateFlow()

    /**
     * 上一版解答快照（会话内，非空 = 结果页可回看「上一版」）：
     * 新一轮求解（重新解题 / 题干修正重解）启动前，若当前结果态有已产出内容则归档至此；
     * 新请求失败且无半成品时据此回滚。换题/恢复历史时清空（新上下文不再相关）。
     */
    private val _previousVersion = MutableStateFlow<SolveSnapshot?>(null)
    val previousVersion: StateFlow<SolveSnapshot?> = _previousVersion.asStateFlow()

    private val _followUpGenerating = MutableStateFlow(false)
    val followUpGenerating: StateFlow<Boolean> = _followUpGenerating.asStateFlow()

    private var followUpJob: Job? = null

    /**
     * 当前界面绑定的历史记录 id（仅内存）。用于追问落库归属：
     * - 新解题保存成功 / 回填历史时赋值；
     * - 换题 / 重新解题开始 / 清空追问时置空（无归属的新轮次仅内存展示，不落库）。
     */
    private var activeRecordId: String? = null

    /** 把当前追问线程写回活动记录（JSON 快照，整列覆盖；最后一轮回答为空则不写） */
    private suspend fun persistFollowUpSnapshot() {
        val recordId = activeRecordId ?: return
        val turns = _followUps.value
        if (turns.isEmpty()) return
        if (turns.last().answer.isBlank()) return
        runCatching {
            solveHistoryDao.updateFollowUps(recordId, encodeFollowUps(turns))
        }
    }

    /** 把追问上下文（题干/过程/作答，均只含非空段）拼成给模型的 system 提示 */
    private fun buildFollowUpContext(): String {
        val question = _resultQuestion.value?.takeIf { it.isNotBlank() }
        val process = _process.value.takeIf { it.isNotBlank() }
        val final = _finalAnswer.value.takeIf { it.isNotBlank() }
        val sb = StringBuilder()
        sb.append(
            "You previously solved a photographed math problem for the user and produced the " +
                "solution below. Use it as context to answer the user's follow-up questions about " +
                "this problem (a specific step, an alternative method, checking an answer, etc.). " +
                "Answer in the same language as the user's question. Do not repeat the full " +
                "original solution unless the user asks for it.\n\n"
        )
        sb.append("## Problem\n").append(question ?: "(no text extracted; rely on image if provided)").append("\n\n")
        sb.append("## Existing solution steps\n").append(process ?: "(none)").append("\n\n")
        sb.append("## Final answer\n").append(final ?: "(none)")
        return sb.toString()
    }

    /**
     * 页内追问：把当前题目的上下文（题干/过程/作答，可选带图）连同问题发给解题模型，
     * 流式回答追加到 [followUps] 最后一个 turn。串行：生成中再发会被忽略（UI 禁发送）。
     * [extraImage] 为用户在追问输入区从相册附加的图（content://）；仅当解题模型支持
     * vision 时随本轮发送，否则忽略并以纯文本提问（附提示）。
     */
    fun askFollowUp(question: String, extraImage: String? = null) {
        val q = question.trim()
        if (q.isEmpty()) return
        if (_followUpGenerating.value || _generating.value) return
        val model = resolveSolveModel(settings.value)
        if (model == null) {
            appScope.launch {
                errorFlow.emit(IllegalStateException("未配置解题模型，请先在模型设置中选择"))
            }
            return
        }
        val supportsVision = model.inputModalities.contains(me.rerere.ai.provider.Modality.IMAGE)
        if (extraImage != null && !supportsVision) {
            appScope.launch {
                errorFlow.emit(IllegalStateException("当前解题模型不支持图片输入，附图已忽略，提问按文字发送"))
            }
        }
        _followUps.value = _followUps.value + FollowUpTurn(question = q, answer = "")
        _followUpGenerating.value = true
        followUpJob = appScope.launch {
            try {
                val images = if (supportsVision) {
                    buildList {
                        addAll(_images.value)
                        if (extraImage != null) add(extraImage)
                    }
                } else {
                    emptyList()
                }
                generationHandler.followUpQuestion(
                    settings = settings.value,
                    question = q,
                    contextText = buildFollowUpContext(),
                    imageUris = images,
                ) { update ->
                    // 覆盖式更新最后一个 turn 的思考与回答（回调载荷为累积全文，非增量）
                    val current = _followUps.value
                    if (current.isNotEmpty()) {
                        _followUps.value = current.dropLast(1) +
                            current.last().copy(reasoning = update.reasoning, answer = update.text)
                    }
                }.collect { /* 结束信号，无额外处理 */ }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "followUp failed", e)
                errorFlow.emit(e)
            } finally {
                // 正常完成与主动停止都保留回答并回写记录快照（空回答不写）
                persistFollowUpSnapshot()
                _followUpGenerating.value = false
            }
        }
    }

    /** 停止当前追问：保留已生成的部分回答，不回滚 */
    fun cancelFollowUp() {
        followUpJob?.cancel()
        _followUpGenerating.value = false
    }

    /** 清空追问会话（换题 / 重新解题 / 恢复历史时旧问答不再匹配新上下文） */
    private fun clearFollowUps() {
        followUpJob?.cancel()
        _followUpGenerating.value = false
        _followUps.value = emptyList()
        activeRecordId = null
    }

    fun updateSettings(settings: Settings) {
        appScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateNoteText(text: String) {
        _noteText.value = text
    }

    /** 当前是否具备发起解题的条件：有图（或有补充文本）且解题模型可用 */
    fun canSolve(): Boolean {
        if (_images.value.isEmpty() && _noteText.value.isBlank()) return false
        return resolveSolveModel(settings.value) != null
    }

    /**
     * 解析解题模型；未配置（UNSET）或模型已失效时返回 null，
     * 由 UI 呈现配置引导而不是发起一次注定失败的请求。
     */
    fun resolveSolveModel(settings: Settings): Model? {
        if (settings.solveModelId == UNSET_MODEL_ID) return null
        return settings.providers.findModelById(settings.solveModelId)
    }

    // ---------- 取景 / 框选 ----------

    /** 拍照或相册取图成功：带着原始 uri 进入框选确认阶段（原图先不落盘） */
    fun startCrop(captureUri: Uri) {
        if (_generating.value) return
        _pendingCapture.value = captureUri.toString()
        _phase.value = SolvePhase.CropConfirm
    }

    /** 框选确认页取消（重拍/重选）：清理临时原图回取景 */
    fun cancelCrop() {
        _pendingCapture.value?.let { deletePendingWork(it) }
        _pendingCapture.value = null
        clearRotateArtifacts()
        _phase.value = SolvePhase.ViewFinder
    }

    /**
     * 框选确认页旋转原图（顺时针 90°，每点一次 +90°）。
     *
     * 根因：竖持手机拍横向卷面是高频场景——图内容整体横置，OCR/视觉识别质量下降，
     * 且 fill 放大后框选困难。实现为「旋转产物替换 pending」而非显示层变换：
     * 旋转结果落盘为新图并替换 [pendingCapture]，显示与后续裁切都基于旋转后图，
     * 坐标系天然一致（框选归一化矩形直接映射旋转后位图），无需在裁切链路传旋转角。
     */
    fun rotatePending(clockwise: Boolean = true) {
        val pending = _pendingCapture.value ?: return
        if (_phase.value != SolvePhase.CropConfirm) return
        if (_generating.value) return
        appScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    rotatePendingImage(Uri.parse(pending), clockwise)
                }
                if (file != null) {
                    // 旋转产物落在独立 solve_rotate 目录（与相机目录分离避免清目录互删）；
                    // 旧 pending（相机缓存或外部相册 uri）保持不动，由 cancel/confirm 统一清理
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file,
                    )
                    _pendingCapture.value = uri.toString()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "rotatePending failed", e)
                errorFlow.emit(e)
            }
        }
    }

    /**
     * 框选确认：把 pending 原图按归一化矩形裁切持久化 → 加入 [images] → 自动发起解题。
     * 归一化矩形 (0..1) 相对「旋转烘焙后的位图」坐标系，与 UI 显示的图面朝向一致。
     */
    fun confirmCropAndSolve(cropRect: RectF) {
        val pending = _pendingCapture.value ?: return
        val model = resolveSolveModel(settings.value)
        if (model == null) {
            appScope.launch {
                errorFlow.emit(IllegalStateException("未配置解题模型，请先在模型设置中选择"))
            }
            return
        }
        if (_generating.value) return
        _phase.value = SolvePhase.Solving
        currentJob = appScope.launch {
            try {
                val persisted = withContext(Dispatchers.IO) {
                    cropPendingImage(Uri.parse(pending), cropRect)
                }
                _pendingCapture.value = null
                // 裁切已持久化，旋转产物目录一并清掉（同一会话不再引用）
                clearRotateArtifacts()
                _images.value = _images.value + persisted.toUri().toString()
                launchSolveInternal(
                    images = _images.value,
                    note = _noteText.value.trim(),
                    model = model,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "confirmCropAndSolve failed", e)
                errorFlow.emit(e)
                // 裁切/解码失败（格式不支持、文件损坏等）：保留 pending 原图回框选页，
                // 用户可直接重新框选或换图重试——清回取景等于要求重拍，重试成本过高。
                _phase.value = SolvePhase.CropConfirm
            }
        }
    }

    /**
     * 结果态换题：清空当前题图、输出与补充描述，回取景重新拍。
     * 语义上等于「新的一题」；历史已落库的不受影响。
     */
    fun retake() {
        currentJob?.cancel()
        resetToViewFinder()
    }

    // ---------- 解题 ----------

    /** 手动触发解题（结果态底部「重新解题」用当前图重跑） */
    fun solve() {
        val images = _images.value
        if (images.isEmpty() && _noteText.value.isBlank()) return
        val model = resolveSolveModel(settings.value)
        if (model == null) {
            appScope.launch {
                errorFlow.emit(IllegalStateException("未配置解题模型，请先在模型设置中选择"))
            }
            return
        }
        if (_generating.value) return
        // 新一轮求解前把当前结果态内容归档为「上一版」：失败回滚与旧解回看共用
        archiveCurrentVersion()
        _phase.value = SolvePhase.Solving
        currentJob = appScope.launch {
            launchSolveInternal(images, _noteText.value.trim(), model)
        }
    }

    /**
     * 题干卡编辑后重解（纠错闭环）：
     * 用户修正题干后重新解题。按解题模型视觉能力分流（根因：OCR 降级模型无视觉，
     * 重发图会再次触发内部 OCR 覆盖掉用户修正，只能纯文本重解；vision 模型能自己读图，
     * 重发原图 + 修正文本作 Additional notes 让模型重读图并对齐用户修正，纠错更可靠——
     * 图片引用记入新历史便于回看）。
     */
    fun resolveWithQuestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (_generating.value) return
        val model = resolveSolveModel(settings.value)
        if (model == null) {
            appScope.launch {
                errorFlow.emit(IllegalStateException("未配置解题模型，请先在模型设置中选择"))
            }
            return
        }
        // 题干修正重解同样是「新一轮」：把基于旧题干的结果归档为上一版，失败可回滚
        archiveCurrentVersion()
        // 立即用修正文本刷新题干卡（避免新一轮生成期间卡内容回跳）
        _resultQuestion.value = trimmed
        _phase.value = SolvePhase.Solving
        val supportsVision = model.inputModalities.contains(Modality.IMAGE)
        currentJob = appScope.launch {
            launchSolveInternal(
                images = if (supportsVision) _images.value else emptyList(),
                note = trimmed,
                model = model,
            )
        }
    }

    fun cancelSolve() {
        currentJob?.cancel()
        // generating / 前台服务的复位在 job 的 finally 中完成，取消路径同样执行 finally
    }

    /**
     * 实际发起 solveQuestion 的任务体：由确认框选、手动重解、题干编辑重解三个入口共用。
     * 状态复位（generating/三段输出）放在这里统一做，入口不再各自重置。
     */
    private suspend fun launchSolveInternal(
        images: List<String>,
        note: String,
        model: Model,
    ) {
        val solveId = UUID.randomUUID().toString()
        currentSolveId = solveId
        // vision 直送 / OCR 降级分流的判定：题干卡数据源与编辑重解策略都依赖它
        val supportsVision = model.inputModalities.contains(Modality.IMAGE)
        // 新解题 = 新上下文：清掉基于旧解答的追问会话
        clearFollowUps()
        _generating.value = true
        _reasoning.value = ""
        _process.value = ""
        _finalAnswer.value = ""
        _reasoningStartAt.value = null
        _reasoningEndAt.value = null

        // 前台服务保活：生成中退到后台不因进程冻结中断流式
        SolveGenerationForegroundService.acquire(context, solveId)

        try {
            var result: SolveResult? = null
            generationHandler.solveQuestion(
                settings = settings.value,
                imageUris = images,
                questionText = note.ifBlank { null },
            ) { update ->
                // 题干卡实时上屏（仅 vision 直送路径）：模型读题块解析到就立即展示——
                // 这是用户判断"AI 有没有读错题"的唯一文本窗口，等整轮结束就太晚了；
                // OCR 路径不实时覆盖，结束后用输入侧权威的 questionText 一次回填。
                if (supportsVision && update.statement.isNotBlank()) {
                    _resultQuestion.value = update.statement
                }
                // 思考计时打点：首个 reasoning chunk 记开始，首个过程/作答 chunk 记结束
                val now = System.currentTimeMillis()
                if (update.reasoning.isNotBlank() && _reasoningStartAt.value == null) {
                    _reasoningStartAt.value = now
                }
                if ((update.process.isNotBlank() || update.finalAnswer.isNotBlank()) &&
                    _reasoningEndAt.value == null
                ) {
                    _reasoningEndAt.value = now
                }
                // 覆盖式更新（回调载荷为当前尝试的累积全文，非增量）
                _reasoning.value = update.reasoning
                _process.value = update.process
                _finalAnswer.value = update.finalAnswer
            }.collect { result = it }

            // 只把真正出结果的解题写入历史（process/final 至少一个非空）
            val finalResult = result
            // 题干卡终值（与 saveRecord 落库共用同一取值，保证「结果页题干卡」与
            // 「历史回看题干卡」一致）：
            // - vision 直送：AI 读题块（statement）优先；模型未遵守协议输出时回落到输入文本；
            // - OCR/纯文本：questionText 权威（编辑重解时即用户修正文本），statement 不复述覆盖。
            val displayQuestion = finalResult?.let { r ->
                if (supportsVision) {
                    r.statement.takeIf { it.isNotBlank() }
                        ?: r.questionText
                        ?: note.ifBlank { null }
                } else {
                    r.questionText ?: note.ifBlank { null }
                }
            }
            if (finalResult != null &&
                (finalResult.process.isNotBlank() || finalResult.finalAnswer.isNotBlank())
            ) {
                // 纯文本重解（题干编辑）时 images 为空，但会话仍持有题图文件，
                // 用会话首图作历史缩略图引用，回看时图不丢。
                val recordImage = images.firstOrNull() ?: _images.value.firstOrNull()
                // 思考耗时：首个 reasoning chunk 至首个过程/作答 chunk（或任务结束）区间；
                // 模型无思考流时两者皆 null，落库 null（历史展示不显示时长）
                val solveReasoningMs = if (_reasoningStartAt.value != null &&
                    _reasoningEndAt.value != null
                ) {
                    _reasoningEndAt.value!! - _reasoningStartAt.value!!
                } else {
                    null
                }
                saveRecord(
                    image = recordImage,
                    questionText = displayQuestion,
                    reasoning = finalResult.reasoning,
                    reasoningMs = solveReasoningMs,
                    process = finalResult.process,
                    finalAnswer = finalResult.finalAnswer,
                    modelId = model.id.toString(),
                )
            }
            // 题干卡兜底回填：流式中 statement 未覆盖到时（无 statement / OCR 路径）这里补齐
            displayQuestion
                ?.takeIf { it.isNotBlank() }
                ?.let { _resultQuestion.value = it }
            _phase.value = SolvePhase.Result
        } catch (e: CancellationException) {
            // 用户主动停止：正常路径，不算错误；留在结果区展示已产出内容
            _phase.value = SolvePhase.Result
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "solve failed", e)
            errorFlow.emit(e)
            // 出错且无任何已产出内容：先回滚到上一版成功结果（若有），否则留在结果区
            // 保留题图供「重新解题」重试。根因：旧实现直接 resetToViewFinder 清图，
            // 网络瞬时错误（限流/超时）就把用户打回重拍起点，且重解失败会连带清掉
            // 上一轮可用答案。
            val hasOutput = _process.value.isNotBlank() ||
                _finalAnswer.value.isNotBlank() || _reasoning.value.isNotBlank()
            if (hasOutput) {
                _phase.value = SolvePhase.Result
            } else {
                val rollback = _previousVersion.value
                if (rollback != null) {
                    applySnapshot(rollback)
                } else {
                    _phase.value = SolvePhase.Result
                }
            }
        } finally {
            // 取消/异常/正常结束兜底：思考中被打断也要补上结束时刻，UI 才能显示已思考时长
            if (_reasoningStartAt.value != null && _reasoningEndAt.value == null) {
                _reasoningEndAt.value = System.currentTimeMillis()
            }
            _generating.value = false
            if (currentSolveId == solveId) currentSolveId = null
            SolveGenerationForegroundService.release(context, solveId)
        }
    }

    // ---------- 历史记录 ----------

    /** 把某条历史回填到当前界面（图片 + 题干 + 思考 + 过程 + 精炼作答），可复制或重新解题 */
    fun restoreRecord(recordId: String) {
        appScope.launch {
            solveHistoryDao.getById(recordId)?.let { record ->
                currentJob?.cancel()
                clearFollowUps()
                _generating.value = false
                _images.value =
                    if (record.imagePath.isBlank()) emptyList()
                    else listOf(File(context.filesDir, record.imagePath).toUri().toString())
                _resultQuestion.value = record.questionText?.takeIf { it.isNotBlank() }
                _noteText.value = ""
                // 恢复思考全文（v51 起落库；老记录为空串）。真实思考起止时刻未落库，
                // 只有总时长 reasoningMs：合成 start/end（end=createdAt, start=createdAt-ms）
                // 使 UI 的 endAt-startAt 差值恰好等于记录时长，无真实时间语义仅作展示。
                _reasoning.value = record.reasoningText
                val hasReasoning = record.reasoningText.isNotBlank()
                _reasoningStartAt.value = if (hasReasoning) {
                    record.reasoningMs?.let { record.createdAt - it } ?: record.createdAt - 1L
                } else {
                    null
                }
                _reasoningEndAt.value = if (hasReasoning) record.createdAt else null
                _process.value = record.processText
                _finalAnswer.value = record.finalText
                _pendingCapture.value = null
                _phase.value = SolvePhase.Result
                // 恢复该记录内嵌的追问线程（与记录同生命周期，列内快照）
                activeRecordId = record.id
                _followUps.value = decodeFollowUps(record.followUpsJson)
                // 历史回填 = 切换上下文，旧会话的「上一版」不再相关
                _previousVersion.value = null
            }
        }
    }

    /**
     * 删除记录（单条/批量共用）：只删 DB 行，题图文件延迟清扫（见 ORPHAN_SWEEP_DELAY_MS
     * 注释——滑动删除带撤销，立即删文件会让撤销后缩略图空图）。
     */
    fun deleteRecords(ids: List<String>) {
        if (ids.isEmpty()) return
        appScope.launch {
            // 追问内嵌在记录行内，随行删除，无需额外处理
            solveHistoryDao.deleteByIds(ids)
            scheduleOrphanSweep()
        }
    }

    fun deleteAllRecords() {
        appScope.launch {
            solveHistoryDao.deleteAll()
            scheduleOrphanSweep()
        }
    }

    /**
     * 单条滑动删除后的撤销：按原 id 原样插回（createdAt 不变，回原时间位置）。
     * 追问作为行内列随记录整体回滚，撤销后追问一并恢复。
     */
    fun undeleteRecord(record: SolveRecordEntity) {
        appScope.launch {
            solveHistoryDao.insertAndTrim(record, HISTORY_LIMIT)
        }
    }

    // ---------- 图片处理 ----------

    /**
     * 裁切 pending 原图并持久化到 filesDir/solve_images。
     *
     * 复用 [ImageUtils.convertHeifToJpeg]：统一处理 HEIC/HEIF 解码、EXIF 旋转烘焙与
     * 4096 尺寸上限——保证落盘位图与 UI 显示（coil 同样按 EXIF 转正）朝向一致，
     * 归一化框选矩形可以直接按像素比例映射，无需再处理旋转坐标系。
     */
    private fun cropPendingImage(pending: Uri, rect: RectF): File {
        val workDir = File(context.cacheDir, SOLVE_WORK_DIR).apply { mkdirs() }
        val workFile = File(workDir, "solve_work_${Uuid.random()}.jpg")
        try {
            val converted = ImageUtils.convertHeifToJpeg(
                context = context,
                uri = pending,
                target = workFile,
                maxSize = MAX_CROP_DIMENSION,
                quality = 95,
            )
            if (!converted) error("无法解码该图片（格式不支持或已损坏），请重试或换一张")
            val bitmap = BitmapFactory.decodeFile(workFile.absolutePath)
                ?: error("图片解码失败，请重试")

            try {
                val width = bitmap.width
                val height = bitmap.height
                val left = (rect.left.coerceIn(0f, 1f) * width)
                    .roundToInt().coerceIn(0, width - MIN_CROP_EDGE)
                val top = (rect.top.coerceIn(0f, 1f) * height)
                    .roundToInt().coerceIn(0, height - MIN_CROP_EDGE)
                val cropWidth = ((rect.right - rect.left) * width).roundToInt()
                    .coerceIn(MIN_CROP_EDGE, width - left)
                val cropHeight = ((rect.bottom - rect.top) * height).roundToInt()
                    .coerceIn(MIN_CROP_EDGE, height - top)
                val cropped = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)

                val dir = File(context.filesDir, SOLVE_IMAGE_DIR).apply { mkdirs() }
                val out = File(dir, "solve_${Uuid.random()}.jpg")
                try {
                    out.outputStream().use { output ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, SOLVE_JPEG_QUALITY, output)
                    }
                } finally {
                    ImageUtils.recycleBitmapSafely(cropped)
                }
                return out
            } finally {
                ImageUtils.recycleBitmapSafely(bitmap)
            }
        } finally {
            workFile.delete()
        }
    }

    /** pending 原图的本地文件清理：file:// 直接删；相机 FileProvider content:// 的
     *  实体统一落在 cacheDir/solve_camera（相机取景页的输出目录），整目录清空即可；
     *  相册选图的 content:// 无本地文件，空操作。 */
    private fun deletePendingWork(uri: String) {
        runCatching {
            if (uri.startsWith("file:")) {
                File(uri.toUri().path ?: return).delete()
            } else if (uri.startsWith("content:")) {
                File(context.cacheDir, SOLVE_CAMERA_DIR)
                    .listFiles()?.forEach { it.delete() }
            }
        }
    }

    /**
     * 把 pending 原图顺时针/逆时针旋转 90° 并落盘到 cacheDir/solve_rotate。
     *
     * 实现：先按 EXIF 烘焙解码（复用 convertHeifToJpeg，与裁切链路同一朝向基准），
     * 再用 Matrix 旋转位图后重编码 JPEG。返回新文件；解码失败返回 null（调用方保留原图）。
     */
    private fun rotatePendingImage(pending: Uri, clockwise: Boolean): File? {
        val workDir = File(context.cacheDir, SOLVE_WORK_DIR).apply { mkdirs() }
        val workFile = File(workDir, "solve_work_${Uuid.random()}.jpg")
        try {
            val converted = ImageUtils.convertHeifToJpeg(
                context = context,
                uri = pending,
                target = workFile,
                maxSize = MAX_CROP_DIMENSION,
                quality = 95,
            )
            if (!converted) {
                error("无法解码该图片（格式不支持或已损坏），请重试或换一张")
            }
            val bitmap = BitmapFactory.decodeFile(workFile.absolutePath)
                ?: error("图片解码失败，请重试")
            try {
                val matrix = Matrix().apply {
                    // Compose 中 RotateRight01 的视觉语义 = 顺时针旋转画面
                    postRotate(if (clockwise) 90f else -90f)
                }
                val rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                try {
                    val dir = File(context.cacheDir, SOLVE_ROTATE_DIR).apply { mkdirs() }
                    val out = File(dir, "solve_rot_${System.currentTimeMillis()}.jpg")
                    out.outputStream().use { output ->
                        rotated.compress(Bitmap.CompressFormat.JPEG, SOLVE_JPEG_QUALITY, output)
                    }
                    return out
                } finally {
                    ImageUtils.recycleBitmapSafely(rotated)
                }
            } finally {
                ImageUtils.recycleBitmapSafely(bitmap)
            }
        } finally {
            workFile.delete()
        }
    }

    /** 清空旋转产物目录（框选取消/确认后旧旋转文件不再被引用，cache 也无需保留） */
    private fun clearRotateArtifacts() {
        runCatching {
            File(context.cacheDir, SOLVE_ROTATE_DIR)
                .listFiles()?.forEach { it.delete() }
        }
    }

    // ---------- 上一版快照 / 失败回滚 ----------

    /**
     * 新一轮求解启动前调用：结果态且已有产出时，把当前内容归档为「上一版」。
     * 半成品（正在生成被 UI 禁用，此处 generating 必为 false）或空结果不归档。
     */
    private fun archiveCurrentVersion() {
        if (_phase.value != SolvePhase.Result) return
        if (_generating.value) return
        val hasContent = _reasoning.value.isNotBlank() ||
            _process.value.isNotBlank() || _finalAnswer.value.isNotBlank()
        if (!hasContent) return
        _previousVersion.value = snapshotOfCurrent()
    }

    /** 取当前界面完整状态为快照（回滚与归档共用同一捕获逻辑） */
    private fun snapshotOfCurrent(): SolveSnapshot = SolveSnapshot(
        images = _images.value,
        resultQuestion = _resultQuestion.value,
        reasoning = _reasoning.value,
        process = _process.value,
        finalAnswer = _finalAnswer.value,
        noteText = _noteText.value,
        reasoningStartAt = _reasoningStartAt.value,
        reasoningEndAt = _reasoningEndAt.value,
        followUps = _followUps.value,
        activeRecordId = activeRecordId,
    )

    /** 把快照整体恢复到界面（新一轮失败回滚；进行中的 followUp job 一并停掉） */
    private fun applySnapshot(snapshot: SolveSnapshot) {
        followUpJob?.cancel()
        _followUpGenerating.value = false
        _images.value = snapshot.images
        _resultQuestion.value = snapshot.resultQuestion
        _reasoning.value = snapshot.reasoning
        _reasoningStartAt.value = snapshot.reasoningStartAt
        _reasoningEndAt.value = snapshot.reasoningEndAt
        _process.value = snapshot.process
        _finalAnswer.value = snapshot.finalAnswer
        _noteText.value = snapshot.noteText
        _followUps.value = snapshot.followUps
        activeRecordId = snapshot.activeRecordId
        _pendingCapture.value = null
        _phase.value = SolvePhase.Result
    }

    // ---------- 孤儿图片延迟清扫 ----------

    /**
     * 记录删除/超限裁剪后调度一次延迟清扫（延迟窗口见 ORPHAN_SWEEP_DELAY_MS）。
     * 幂等：清扫依据「执行时刻的引用集」（历史记录 imagePath ∪ 当前会话 images），
     * 被撤销的记录重新出现则其文件被保留，窗口内多次调度收敛为一次清扫。
     */
    private fun scheduleOrphanSweep() {
        appScope.launch {
            delay(ORPHAN_SWEEP_DELAY_MS)
            sweepOrphanImages()
        }
    }

    /** 删除 solve_images 下未被任何记录引用、也不属于当前会话的图片文件 */
    private fun sweepOrphanImages() {
        runCatching {
            val referenced = records.value.mapNotNull { it.imagePath }.toSet()
            val sessionFiles = buildSet {
                _images.value.forEach { uri ->
                    resolveImageFile(uri)?.let { add(it.absolutePath) }
                }
            }
            val dir = File(context.filesDir, SOLVE_IMAGE_DIR)
            if (!dir.exists()) return@runCatching
            dir.listFiles()?.forEach { file ->
                val relative = runCatching { file.relativeTo(context.filesDir).path }.getOrNull()
                if (relative == null || relative in referenced) return@forEach
                if (file.absolutePath in sessionFiles) return@forEach
                file.delete()
            }
        }
    }

    /** 回到取景的公共复位：清图、清输出、清补充，保证 ViewFinder 阶段 images 恒为空 */
    private fun resetToViewFinder() {
        cleanupCurrentImages()
        clearFollowUps()
        _images.value = emptyList()
        _noteText.value = ""
        _reasoning.value = ""
        _reasoningStartAt.value = null
        _reasoningEndAt.value = null
        _process.value = ""
        _finalAnswer.value = ""
        _resultQuestion.value = null
        _pendingCapture.value = null
        _previousVersion.value = null
        _phase.value = SolvePhase.ViewFinder
    }

    /** 当前会话图片（images）对应的私有文件清理：retake 时释放磁盘。
     *  只删未被任何历史记录引用的文件——历史 imagePath 与当前会话文件同源，
     *  直接删会把刚解题成功并已落库的题图一起删掉，历史卡片将出现空图。 */
    private fun cleanupCurrentImages() {
        val referenced = records.value.mapNotNull { it.imagePath }.toSet()
        _images.value.forEach { uri ->
            val file = resolveImageFile(uri) ?: return@forEach
            val relative = runCatching { file.relativeTo(context.filesDir).path }.getOrNull()
            if (relative == null || relative !in referenced) file.delete()
        }
    }

    /** 兼容传相对路径（记录里）或 file:// uri（VM 状态里），解析为私有目录下的文件 */
    private fun resolveImageFile(pathOrUri: String): File? {
        return runCatching {
            if (pathOrUri.startsWith("file:")) {
                pathOrUri.toUri().toFile()
            } else {
                File(context.filesDir, pathOrUri)
            }
        }.getOrNull()
    }

    private suspend fun saveRecord(
        image: String?,
        questionText: String?,
        reasoning: String,
        reasoningMs: Long?,
        process: String,
        finalAnswer: String,
        modelId: String,
    ) {
        // imagePath 为空串仅当纯文本解题（无图）；有图时存 filesDir 相对路径
        val relativePath = image?.let { resolveImageFile(it)?.relativeTo(context.filesDir)?.path } ?: ""
        val record = SolveRecordEntity(
            id = UUID.randomUUID().toString(),
            imagePath = relativePath,
            questionText = questionText,
            reasoningText = reasoning,
            reasoningMs = reasoningMs,
            processText = process,
            finalText = finalAnswer,
            modelId = modelId,
            createdAt = System.currentTimeMillis(),
        )
        val trimmedIds = solveHistoryDao.insertAndTrim(
            record = record,
            keep = HISTORY_LIMIT
        )
        // 超限裁剪掉的老记录：其题图立即成为孤儿（无撤销语义，历史自动淘汰），
        // 走延迟清扫统一回收（清扫按引用集判断，幂等安全）
        if (trimmedIds.isNotEmpty()) scheduleOrphanSweep()
        // 当前界面与这条记录绑定：此后追问落库归属它
        activeRecordId = record.id
    }
}

/**
 * 追问线程 → JSON 数组字符串（solve_history.follow_ups 列格式）。
 * 字段名与解码方约定：question / reasoning / answer。
 */
private fun encodeFollowUps(turns: List<FollowUpTurn>): String {
    val arr = JSONArray()
    turns.forEach { turn ->
        arr.put(
            JSONObject()
                .put("question", turn.question)
                .put("reasoning", turn.reasoning)
                .put("answer", turn.answer)
        )
    }
    return arr.toString()
}

/** JSON 数组字符串 → 追问线程；解析失败返回空（视为无追问，不阻断回填）。
 *  老记录无 reasoning 键时按空处理，兼容默认 "[]" 之前版本。 */
private fun decodeFollowUps(json: String): List<FollowUpTurn> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    FollowUpTurn(
                        question = obj.optString("question"),
                        reasoning = obj.optString("reasoning"),
                        answer = obj.optString("answer"),
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
