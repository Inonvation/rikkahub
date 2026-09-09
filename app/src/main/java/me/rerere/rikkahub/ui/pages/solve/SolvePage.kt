package me.rerere.rikkahub.ui.pages.solve

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.CameraRotated01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Note
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.RotateRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.unwrapWrappedLatexBlock
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.message.ReasoningHeaderRow
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.explainErrorText
import me.rerere.rikkahub.utils.extractThinkingTitle
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.uuid.Uuid

/**
 * 拍照解题工作台（v2 拍题闭环版）。
 *
 * 页面 = 显式状态机驱动的四阶段全屏形态（详见 [SolvePhase]）：
 * - ViewFinder  : 全屏相机取景（拍题首页，取代 v1 的表单入口卡）
 * - CropConfirm : 原图框选确认（应用内拖拽矩形，取代 uCrop 外部裁剪 Activity）
 * - Solving     : 自动解题（框选确认即触发，无「开始解题」按钮）
 * - Result      : 题图摘要 + 题干/思考/解答/作答 + 重新解题
 * 历史是页面内子模式（复用同一 VM 做记录回填，与 v1 一致），不影响 phase 流转。
 */
@Composable
fun SolvePage(vm: SolveVM = koinInject()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val pendingCapture by vm.pendingCapture.collectAsStateWithLifecycle()
    val images by vm.images.collectAsStateWithLifecycle()
    val noteText by vm.noteText.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val reasoning by vm.reasoning.collectAsStateWithLifecycle()
    val reasoningStartAt by vm.reasoningStartAt.collectAsStateWithLifecycle()
    val reasoningEndAt by vm.reasoningEndAt.collectAsStateWithLifecycle()
    val process by vm.process.collectAsStateWithLifecycle()
    val finalAnswer by vm.finalAnswer.collectAsStateWithLifecycle()
    val resultQuestion by vm.resultQuestion.collectAsStateWithLifecycle()
    val records by vm.records.collectAsStateWithLifecycle()
    val followUps by vm.followUps.collectAsStateWithLifecycle()
    val followUpGenerating by vm.followUpGenerating.collectAsStateWithLifecycle()
    val previousVersion by vm.previousVersion.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val deletedRecordMessage = stringResource(R.string.photo_solve_record_deleted)
    val undoLabel = stringResource(R.string.photo_solve_undo)
    val solveModel = vm.resolveSolveModel(settings)

    val imageUri = images.firstOrNull()

    // 历史子模式（页面级 UI 状态；进入/退出不影响 VM 的解题 phase）
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var showBatchDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteAllDialog by rememberSaveable { mutableStateOf(false) }
    // 取景态补充说明编辑弹层（页面级 UI 状态，进入不销毁相机组合，返回即收起）
    var showNoteSheet by remember { mutableStateOf(false) }

    fun exitSelection() {
        selecting = false
        selectedIds.clear()
    }

    // 相册取图（ViewFinder 与 CropConfirm 共用：取到即进框选）
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.startCrop(uri)
    }

    // 返回键优先级：历史多选 → 历史视图 → 框选确认（取消回取景）
    BackHandler(enabled = selecting) { exitSelection() }
    BackHandler(enabled = showHistory && !selecting) { showHistory = false }
    BackHandler(enabled = !showHistory && phase == SolvePhase.CropConfirm) { vm.cancelCrop() }

    LaunchedEffect(Unit) {
        vm.errorFlow.collect { error ->
            toaster.show(explainErrorText(error.message), type = ToastType.Error)
        }
    }

    fun openHistory() {
        showHistory = true
        exitSelection()
    }

    fun copyFinal() {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, finalAnswer)))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 页面四模式间交叉淡化过渡（根因：框选确认 → 解题 等模式切换原先硬切、
        // 无任何转场，确认瞬间直接跳到新界面观感突兀；Crossfade 做 240ms 淡入淡出，
        // 不引入滑动方向语义，与页面其余克制转场保持一致）
        Crossfade(
            targetState = when {
                showHistory -> SolvePageMode.History
                phase == SolvePhase.ViewFinder -> SolvePageMode.ViewFinder
                phase == SolvePhase.CropConfirm -> SolvePageMode.Crop
                else -> SolvePageMode.Work
            },
            animationSpec = tween(240),
            label = "solvePageMode",
        ) { mode ->
            when (mode) {
                // ---------- 历史子模式 ----------
                SolvePageMode.History -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TopAppBar(
                            title = { Text(stringResource(R.string.photo_solve_history_title)) },
                            navigationIcon = {
                                IconButton(onClick = {
                                    if (selecting) exitSelection() else showHistory = false
                                }) {
                                    Icon(
                                        imageVector = HugeIcons.ArrowLeft01,
                                        contentDescription = stringResource(R.string.photo_solve_history_back)
                                    )
                                }
                            },
                            actions = {
                                if (!selecting && records.isNotEmpty()) {
                                    IconButton(onClick = { selecting = true }) {
                                        Icon(
                                            HugeIcons.MoreVertical,
                                            contentDescription = stringResource(R.string.photo_solve_history_batch_select)
                                        )
                                    }
                                    IconButton(onClick = { showDeleteAllDialog = true }) {
                                        Icon(
                                            HugeIcons.Delete01,
                                            contentDescription = stringResource(R.string.photo_solve_history_delete_all)
                                        )
                                    }
                                }
                            }
                        )
                        SolveHistoryList(
                            records = records,
                            selecting = selecting,
                            selectedIds = selectedIds,
                            onSelectChange = { id ->
                                if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
                            },
                            onOpenRecord = { record ->
                                vm.restoreRecord(record.id)
                                showHistory = false
                            },
                            onLongPressRecord = { record ->
                                if (record.id !in selectedIds) {
                                    selectedIds.add(record.id)
                                }
                                selecting = true
                            },
                            onDeleteRecord = { record ->
                                // 左滑单条删除：先删后给撤销入口（误滑兜底）
                                scope.launch {
                                    vm.deleteRecords(listOf(record.id))
                                    val result = snackbarHostState.showSnackbar(
                                        message = deletedRecordMessage,
                                        actionLabel = undoLabel,
                                        withDismissAction = true,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        vm.undeleteRecord(record)
                                    }
                                }
                            },
                            onSelectAllToggle = {
                                if (selectedIds.size == records.size) {
                                    selectedIds.clear()
                                } else {
                                    selectedIds.clear()
                                    selectedIds.addAll(records.map { it.id })
                                }
                            },
                            onCancelSelect = { exitSelection() },
                            onDeleteSelectedClick = {
                                if (selectedIds.isNotEmpty()) {
                                    showBatchDeleteDialog = true
                                }
                            },
                        )
                    }
                }

                // ---------- 取景 ----------
                SolvePageMode.ViewFinder -> {
                    // 顶栏与取景区分层（根因：悬浮控件叠在取景画面上，与照片内容重叠遮挡）
                    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        FloatingCameraTopBar(
                            onHistory = ::openHistory,
                            onModelSelect = { id ->
                                vm.updateSettings(settings.copy(solveModelId = id))
                            },
                            onNoteEdit = { showNoteSheet = true },
                            settings = settings,
                            // 取景首屏补页面标题，避免只有一排图标没有语境（重构 P3）
                            title = stringResource(R.string.photo_solve_page_title),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            SolveCameraView(
                                onImageCaptured = vm::startCrop,
                                onPickFromGallery = { galleryLauncher.launch("image/*") },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // ---------- 框选确认 ----------
                SolvePageMode.Crop -> {
                    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        FloatingCameraTopBar(
                            onBack = { vm.cancelCrop() },
                            onHistory = ::openHistory,
                            onModelSelect = { id ->
                                vm.updateSettings(settings.copy(solveModelId = id))
                            },
                            settings = settings,
                            title = stringResource(R.string.photo_solve_crop_title),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            SolveCropOverlay(
                                imageUri = pendingCapture,
                                noteText = noteText,
                                modelConfigured = solveModel != null,
                                onNoteChange = vm::updateNoteText,
                                onRotate = vm::rotatePending,
                                onConfirm = vm::confirmCropAndSolve,
                                onRetake = { vm.cancelCrop() },
                                onPickFromGallery = { galleryLauncher.launch("image/*") },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                // ---------- 解题中 / 结果 ----------
                else -> {
                    SolveWorkScreen(
                        phase = phase,
                        imageUri = imageUri,
                        generating = generating,
                        reasoning = reasoning,
                        reasoningStartAt = reasoningStartAt,
                        reasoningEndAt = reasoningEndAt,
                        process = process,
                        finalAnswer = finalAnswer,
                        resultQuestion = resultQuestion,
                        solveModelConfigured = solveModel != null,
                        onHistory = ::openHistory,
                        onModelSelect = { id ->
                            vm.updateSettings(settings.copy(solveModelId = id))
                        },
                        settings = settings,
                        onRetake = vm::retake,
                        onSolveAgain = vm::solve,
                        onResolveQuestion = vm::resolveWithQuestion,
                        onCopyFinal = ::copyFinal,
                        onStop = vm::cancelSolve,
                        followUps = followUps,
                        followUpGenerating = followUpGenerating,
                        previousVersion = previousVersion,
                        onAskFollowUp = vm::askFollowUp,
                        onCancelFollowUp = vm::cancelFollowUp,
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 84.dp),
        )
    }

    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text(stringResource(R.string.photo_solve_delete_selected_title)) },
            text = { Text(stringResource(R.string.photo_solve_delete_selected_message, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds.toList()
                    showBatchDeleteDialog = false
                    exitSelection()
                    scope.launch {
                        vm.deleteRecords(ids)
                        toaster.show(context.getString(R.string.photo_solve_deleted, ids.size))
                    }
                }) {
                    Text(stringResource(R.string.photo_solve_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text(stringResource(R.string.photo_solve_cancel))
                }
            }
        )
    }

    if (showNoteSheet) {
        // 取景态补充说明编辑：拍前可先写好（vision 直送路径下它是唯一题干文本通道），
        // 内容直写 VM，框选确认页的输入框与这里同源、自动带出；下滑/外部即收起
        ModalBottomSheet(onDismissRequest = { showNoteSheet = false }) {
            NoteEditSheet(
                text = noteText,
                onTextChange = vm::updateNoteText,
            )
        }
    }
}

/**
 * 取景/框选阶段的顶栏（实心黑底通栏，不再悬浮在取景画面上）：
 * 返回（默认弹栈或自定义）+ 标题 + [备注(仅取景)] + 历史 + 模型选择。
 * ModelSelector 在深色条上以主题色呈现，可辨识。
 * 备注入口只挂取景态：vision 直送路径下补充说明是唯一题干文本通道，拍前可先写好，
 * 框选确认页自动带出（两态共享 VM.noteText）。
 */
@Composable
private fun FloatingCameraTopBar(
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onHistory: () -> Unit,
    onModelSelect: (Uuid) -> Unit,
    onBack: (() -> Unit)? = null,
    onNoteEdit: (() -> Unit)? = null,
    title: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            if (onBack != null) {
                BackButton(onClick = onBack)
            } else {
                BackButton()
            }
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (onNoteEdit != null) {
                IconButton(onClick = onNoteEdit) {
                    Icon(
                        imageVector = HugeIcons.Note,
                        contentDescription = stringResource(R.string.photo_solve_note_entry),
                        tint = Color.White,
                    )
                }
            }
            IconButton(onClick = onHistory) {
                Icon(
                    imageVector = HugeIcons.Clock02,
                    contentDescription = stringResource(R.string.photo_solve_history_open),
                    tint = Color.White,
                )
            }
            ModelSelector(
                modelId = settings.solveModelId,
                onSelect = { model -> onModelSelect(model.id) },
                providers = settings.providers,
                type = ModelType.CHAT,
                onlyIcon = true,
            )
        }
    }
}

/**
 * 补充说明编辑弹层（取景态顶栏备注入口打开）：
 * 拍前可写「只求第 3 问」等约束；输入直写 VM.noteText（与框选确认页输入框同源），
 * 无显式保存——关闭即生效。键盘弹出用 imePadding 上推，不遮输入。
 */
@Composable
private fun NoteEditSheet(
    text: String,
    onTextChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.photo_solve_note_sheet_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 2,
                    maxLines = 8,
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.photo_solve_note_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }
}

/**
 * 文档流区块间的分隔线：结果页各区块（题图/题干/思考/解答…）不再用卡片包裹，
 * 改为这条低透明度分隔线定界（重构根因：卡片堆叠切碎"解答稿"的文档语义，
 * 且每卡独立圆角+边框+内边距严重浪费纵向空间，同一行放不下更多内容）。
 */
@Composable
private fun SolveSectionDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

/**
 * 解题中 / 结果工作台（Scaffold + 顶部常规栏 + 分隔线文档流输出区 + 底部操作条）。
 * 输出区结构：题图 / 题干 / 提示 / 解答等区块纵向排列，区块间以 [SolveSectionDivider]
 * 分隔（无卡壳、无间距堆叠）；每个区块自带一致的纵向内边距维持呼吸感。
 */
@Composable
private fun SolveWorkScreen(
    phase: SolvePhase,
    imageUri: String?,
    generating: Boolean,
    reasoning: String,
    reasoningStartAt: Long?,
    reasoningEndAt: Long?,
    process: String,
    finalAnswer: String,
    resultQuestion: String?,
    solveModelConfigured: Boolean,
    onHistory: () -> Unit,
    onModelSelect: (Uuid) -> Unit,
    settings: me.rerere.rikkahub.data.datastore.Settings,
    onRetake: () -> Unit,
    onSolveAgain: () -> Unit,
    onResolveQuestion: (String) -> Unit,
    onCopyFinal: () -> Unit,
    onStop: () -> Unit,
    followUps: List<FollowUpTurn>,
    followUpGenerating: Boolean,
    previousVersion: SolveSnapshot?,
    onAskFollowUp: (String, String?) -> Unit,
    onCancelFollowUp: () -> Unit,
) {
    val isResult = phase == SolvePhase.Result

    // ---------- 思考内容展示（区块 + 独立弹窗） ----------
    // 思考流式进行中 = 生成中且思考已开始、过程/作答尚未到达（与聊天 Reasoning.finishedAt 语义对齐）
    val reasoningStreaming = generating && reasoning.isNotBlank() && reasoningEndAt == null

    // 首块输出前的等待计时（generating 但无任何输出内容时展示「已等待 n 秒」）：
    // 根因——从确认到首个 chunk 常需数秒到十余秒（图片处理 + 首 token），无限波浪条
    // 无法让用户判断「还在跑」还是「卡死」，秒数增长提供确定性感知。
    var waitSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(generating) {
        if (!generating) return@LaunchedEffect
        waitSeconds = 0
        val startMs = System.currentTimeMillis()
        while (true) {
            val elapsedMs = System.currentTimeMillis() - startMs
            waitSeconds = (elapsedMs / 1000).toInt()
            if (elapsedMs >= 5 * 60_000L) break // 5 分钟后停止增长，避免无限 tick
            kotlinx.coroutines.delay(500)
        }
    }

    // 生成中每 200ms 刷新计时（与聊天相同节奏），完成后用 endAt-startAt 定值
    var durationTickMs by remember { mutableStateOf(0L) }
    LaunchedEffect(reasoningStreaming) {
        if (!reasoningStreaming) return@LaunchedEffect
        while (true) {
            durationTickMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(200)
        }
    }
    val reasoningDurationMs: Long? = when {
        reasoningStartAt == null -> null
        reasoningEndAt != null -> reasoningEndAt - reasoningStartAt
        reasoningStreaming -> (durationTickMs - reasoningStartAt).coerceAtLeast(0L)
        else -> null
    }
    // 思考标题（部分模型会在思考文本里带标题）：仅流式期间展示，完成后显示「思考了 n 秒」
    val thinkingTitle = remember(reasoning) { reasoning.extractThinkingTitle() }
    // 思考全文用弹窗承载（根因：思考内容常很长，展开在页面内会长时间占据阅读区；
    // 弹窗内独立滚动，流式生成中自动贴底，互不干扰页面排版）
    var showReasoningSheet by remember { mutableStateOf(false) }
    // 上一版解答弹窗（重解后旧解回看，只读）
    var showPreviousVersion by remember { mutableStateOf(false) }

    // ---------- 结果页页内追问 UI 状态 ----------
    // 追问输入以「对话框式输入条」常驻底栏（无独立开/关模式，随时可追问）；
    // 问答内容始终挂在文档流（解答区块之后），输入只是入口，不丢历史。
    var followUpDraft by remember { mutableStateOf("") }
    // 追问可附带一张相册图（随本轮问题发送；vision 模型可见）
    var followUpAttachUri by remember { mutableStateOf<String?>(null) }
    // 发送后强制滚动到底部（把新回答带进视口）；生成中跟随逻辑见下方 LaunchedEffect
    var followScrollPin by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val followUpGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) followUpAttachUri = uri.toString()
    }

    // 结果态是否有内容可追问（无图、无题干、无解答的空产出时追问无上下文）
    val canAskFollowUp = isResult && (imageUri != null ||
        !resultQuestion.isNullOrBlank() ||
        process.isNotBlank() ||
        finalAnswer.isNotBlank())

    // 底栏常驻输入条的发送闭包：清键盘 → 置滚动锚点 → 交给 VM；草稿清空由输入条自身完成
    fun sendFollowUp(question: String, attach: String?) {
        if (question.isBlank()) return
        keyboardController?.hide()
        followScrollPin = true
        onAskFollowUp(question, attach)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.photo_solve_page_title)) },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(onClick = onHistory) {
                        Icon(HugeIcons.Clock02, contentDescription = stringResource(R.string.photo_solve_history_open))
                    }
                    ModelSelector(
                        modelId = settings.solveModelId,
                        onSelect = { model -> onModelSelect(model.id) },
                        providers = settings.providers,
                        type = ModelType.CHAT,
                        onlyIcon = true,
                    )
                }
            )
        },
        bottomBar = {
            // 底部操作条：生成中 = 居中「停止」胶囊；结果态 = 对话框式常驻追问输入条
            //（未激活单行输入 + 换一题图标，聚焦/有草稿时展开附图/发送/停止）；
            // 空产出时只留「换一题」低调出口。条件渲染替代 AnimatedVisibility 叠放
            //（该 Compose 版本 Box 内无扩展 + 高度跳变）。
            Column(modifier = Modifier.imePadding()) {
                SolveSectionDivider()
                if (generating) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .height(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            OutlinedButton(
                                onClick = onStop,
                                shape = RoundedCornerShape(percent = 50),
                                modifier = Modifier.height(44.dp),
                            ) {
                                Text(stringResource(R.string.photo_solve_cancel))
                            }
                        }
                    }
                } else if (canAskFollowUp) {
                    // 结果态底栏 = 对话框式常驻输入条：输入即追问，无独立开/关模式；
                    // 输入框为主体、「换一题」降为行内低调图标（附图/发送-停止按需出现）
                    FollowUpDialogBar(
                        draft = followUpDraft,
                        attachImageUri = followUpAttachUri,
                        generating = followUpGenerating,
                        onDraftChange = { followUpDraft = it },
                        onAttachChange = { followUpAttachUri = it },
                        onPickImage = { followUpGalleryLauncher.launch("image/*") },
                        onSend = { q, attach ->
                            followUpDraft = ""
                            followUpAttachUri = null
                            sendFollowUp(q, attach)
                        },
                        onStop = onCancelFollowUp,
                        onRetake = onRetake,
                    )
                } else if (isResult) {
                    // 空产出兜底（无图无题干无解答，追问无上下文）：只留低调「换一题」
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .height(44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(onClick = onRetake) {
                            Icon(
                                imageVector = HugeIcons.CameraRotated01,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.photo_solve_new_question))
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        val pageScrollState = rememberScrollState()
        // 滚动内容容器在窗口中的 Y：供「直达作答」换算作答块的页面内偏移
        var scrollContainerWindowY by remember { mutableFloatStateOf(0f) }
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(pageScrollState)
                .imePadding()
                .onGloballyPositioned { coordinates ->
                    scrollContainerWindowY = coordinates.positionInWindow().y
                }
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            // ---------- 区块 1：题图（恒显） ----------
            ImageThumbStrip(
                imageUri = imageUri,
                generating = generating,
            )

            // 上一版解答入口：新一轮求解（重解/题干修正重解）成功后仍可回看旧解，
            // 避免「换种解法再看」把上一版彻底冲掉（会话内保留，不落库）
            if (isResult && !generating && previousVersion != null &&
                (previousVersion.process.isNotBlank() || previousVersion.finalAnswer.isNotBlank())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPreviousVersion = true }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Idea01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = stringResource(R.string.photo_solve_previous_view),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = stringResource(R.string.photo_solve_previous_compare_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = HugeIcons.ArrowRight01,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val hasOutput = reasoning.isNotBlank() || process.isNotBlank() || finalAnswer.isNotBlank()

            // ---------- 区块 2：题干卡（两条路径都有内容可展示：
            // vision 直送 = AI 读题块 statement（流式实时）；OCR 降级 = 回传题干文本） ----------
            resultQuestion?.takeIf { it.isNotBlank() }?.let { question ->
                SolveSectionDivider()
                QuestionCard(
                    question = question,
                    generating = generating,
                    onResolve = onResolveQuestion,
                    // 重新解题随题干卡进入「纠错语境」（与「修正」同排）：
                    // 底栏已让位给对话框式追问输入，重解不再是底栏主操作
                    onSolveAgain = onSolveAgain,
                )
            }

            // ---------- 提示区块：模型未配置（不再是错误容器卡） ----------
            if (!solveModelConfigured && !generating) {
                SolveSectionDivider()
                Text(
                    text = stringResource(R.string.photo_solve_model_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                )
            }

            // ---------- 解题刚启动（尚无任何内容）的等待指示（内联，无卡壳） ----------
            if (generating && !hasOutput) {
                SolveSectionDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearWavyProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                    )
                    Text(
                        text = if (waitSeconds > 0) {
                            stringResource(R.string.photo_solve_solving_wait, waitSeconds)
                        } else {
                            stringResource(R.string.photo_solve_solving)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- 区块 3：思考（流式 = 头部 + 尾部预览；完成 = 仅头部，全文在弹窗） ----------
            if (reasoning.isNotBlank()) {
                SolveSectionDivider()
                ReasoningCard(
                    reasoning = reasoning,
                    streaming = reasoningStreaming,
                    title = if (reasoningStreaming) thinkingTitle else null,
                    durationMs = reasoningDurationMs,
                    onOpen = { showReasoningSheet = true },
                )
            }

            // ---------- 区块 4：解答（过程 + 高亮作答块，头部行承载折叠与复制） ----------
            if (process.isNotBlank() || finalAnswer.isNotBlank()) {
                SolveSectionDivider()
                SolutionCard(
                    process = process,
                    finalAnswer = finalAnswer,
                    generating = generating,
                    scrollState = pageScrollState,
                    onCopyFinal = onCopyFinal,
                    containerWindowY = scrollContainerWindowY,
                )
            }

            // ---------- 区块 5：页内追问线程（基于本题上下文的多轮 Q&A，挂在解答之后） ----------
            if (isResult && followUps.isNotEmpty()) {
                SolveSectionDivider()
                FollowUpThread(
                    turns = followUps,
                    answering = followUpGenerating,
                )
            }

            // 追问流式期间若用户在底部则跟随（与解答流式跟随同策略，避免阅读时被强拉）
            // 追问跟随：发送后（pin）无条件滚到底；流式期间用户在底部才继续跟随，
            // 用户上翻阅读时不被拉扯
            LaunchedEffect(followUps, followUpGenerating, followScrollPin) {
                if (!followUpGenerating) {
                    followScrollPin = false
                    return@LaunchedEffect
                }
                val nearBottom = pageScrollState.maxValue - pageScrollState.value <
                    FOLLOW_BOTTOM_THRESHOLD_PX
                if (followScrollPin || nearBottom) {
                    followScrollPin = false
                    pageScrollState.animateScrollTo(pageScrollState.maxValue)
                }
            }

            // ---------- 结果态空产出兜底：不要让用户面对只有图没有字的死界面 ----------
            if (isResult && !generating && !hasOutput) {
                SolveSectionDivider()
                Text(
                    text = stringResource(R.string.photo_solve_empty_result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }

            // 文档流末端留白（根因：末块（作答/追问/空态）若直到底部操作条分隔线，
            // 滚动到底时内容与操作区"顶死"，作答块的下边距视觉被吃掉——文档流各区块
            // 靠自身 padding(≈10dp) 呼吸，最后一块与底栏之间也应有等量甚至更充分的空隙）
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // 思考全文弹窗：独立滚动，生成中自动贴底跟随；正文 MarkdownBlock 渲染（markdown/LaTeX）
    if (showReasoningSheet && reasoning.isNotBlank()) {
        ModalBottomSheet(onDismissRequest = { showReasoningSheet = false }) {
            ReasoningSheetContent(
                reasoning = reasoning,
                streaming = reasoningStreaming,
                title = if (reasoningStreaming) thinkingTitle else null,
                durationMs = reasoningDurationMs,
                onClose = { showReasoningSheet = false },
            )
        }
    }

    // 上一版解答弹窗：只读快照（思考/过程/作答），与主解答同一 Markdown 渲染
    if (showPreviousVersion) {
        val previous = previousVersion
        if (previous != null) {
            ModalBottomSheet(onDismissRequest = { showPreviousVersion = false }) {
                PreviousVersionSheetContent(
                    previous = previous,
                    onClose = { showPreviousVersion = false },
                )
            }
        } else {
            // previousVersion 已清空（换题/恢复历史）而弹窗还开着：直接关闭避免空壳
            LaunchedEffect(Unit) { showPreviousVersion = false }
        }
    }
}

/**
 * 思考全文弹窗内容：头部行（点击关闭）+ 可滚动 Markdown 全文。
 * 与聊天思考渲染同源（MarkdownBlock，LaTeX/代码块可用）；流式中每 chunk
 * 无动画贴底（对齐聊天 ReasoningContent 跟随策略），结束后可长按选择文本。
 */
@Composable
private fun ReasoningSheetContent(
    reasoning: String,
    streaming: Boolean,
    title: String?,
    durationMs: Long?,
    onClose: () -> Unit,
) {
    val innerScroll = remember { ScrollState(0) }
    // 流式贴底跟随带「用户在底部才贴」判断（根因：弹窗是阅读场景，无条件贴底会在
    // 用户上翻读早期内容时被新 chunk 强拉到底部；与文档流 SolutionCard 同一策略）。
    // 用户离开底部后不再跟随，直到他主动滚回底部区域。
    LaunchedEffect(reasoning, streaming) {
        if (streaming) {
            val nearBottom = innerScroll.maxValue - innerScroll.value <
                REASONING_SHEET_FOLLOW_THRESHOLD_PX
            if (nearBottom) innerScroll.scrollTo(innerScroll.maxValue)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
    ) {
        ReasoningHeaderRow(
            title = title,
            duration = (durationMs ?: 0L).toDuration(DurationUnit.MILLISECONDS),
            loading = streaming,
            contentVisible = true,
            folded = false,
            onClick = onClose,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(innerScroll)
                    .padding(vertical = 12.dp),
            ) {
                val content: @Composable () -> Unit = {
                    MarkdownBlock(
                        content = reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (streaming) content() else SelectionContainer { content() }
            }
        }
    }
}

/** 页面全屏模式（Crossfade target）：与 SolvePhase 解耦——历史是多页覆盖态 */
private enum class SolvePageMode { History, ViewFinder, Crop, Work }

private const val FOLLOW_BOTTOM_THRESHOLD_PX = 600

/**
 * 思考全文弹窗的跟随阈值（px）：用户滚动位置距底部小于该值才随流式贴底。
 * 弹窗是用户主动打开阅读的场景，阈值取小（约两行高），一旦上翻即停止拉扯。
 */
private const val REASONING_SHEET_FOLLOW_THRESHOLD_PX = 200

/**
 * 上一版解答弹窗内容：只读展示归档的旧解快照（题干/思考/过程/作答，有则显示），
 * 与主解答同源 MarkdownBlock 渲染，可长按选择文本。
 */
@Composable
private fun PreviousVersionSheetContent(
    previous: SolveSnapshot,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.photo_solve_previous_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.photo_solve_previous_close))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    previous.resultQuestion
                        ?.takeIf { it.isNotBlank() }
                        ?.let { question ->
                            SectionLabel(stringResource(R.string.photo_solve_question_title))
                            // 题干同样去整段 latex 外壳后渲染（与主题干卡一致）
                            MarkdownBlock(
                                content = unwrapWrappedLatexBlock(question),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    if (previous.reasoning.isNotBlank()) {
                        SectionLabel(stringResource(R.string.photo_solve_reasoning_sheet_title))
                        MarkdownBlock(
                            content = previous.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (previous.process.isNotBlank()) {
                        SectionLabel(stringResource(R.string.photo_solve_solution_title))
                        MarkdownBlock(
                            content = previous.process,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (previous.finalAnswer.isNotBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                    RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            SectionLabel(stringResource(R.string.photo_solve_final_title))
                            MarkdownBlock(
                                content = previous.finalAnswer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 弹窗内容小节的小标题（labelMedium 弱色） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 页面内预览的最大高度：只露出文本尾部（最新流式内容），对齐聊天 100dp */
private val REASONING_PREVIEW_HEIGHT = 100.dp

/**
 * 思考过程区块（文档流版，无卡壳；页面内保持最小占位，全文走独立弹窗）：
 * - 流式生成中：头部（轮换文案 + 已思考秒数）+ 固定高度尾部预览（最新内容贴底，
 *   顶部渐隐遮罩用页面背景色）；此阶段头部不挂点击，避免打断滚动预览。
 * - 完成：仅头部行「思考了 n 秒」（默认折叠不占页面空间），右侧「查看全文」提示，
 *   点击头部行打开全文弹窗；弹窗正文用 MarkdownBlock 渲染（markdown/LaTeX 可读）。
 *   （根因取舍：思考全文往往很长，完成态直接铺在页面内会把解答/作答挤到首屏之外，
 *   弹窗承载全文、页面只留一行入口，两者都不阻塞阅读。）
 */
@Composable
private fun ReasoningCard(
    reasoning: String,
    streaming: Boolean,
    title: String?,
    durationMs: Long?,
    onOpen: () -> Unit,
) {
    // 预览态的内部滚动：每 chunk 无动画贴底，对齐聊天 ReasoningContent 的跟随策略
    val innerScroll = remember { ScrollState(0) }
    LaunchedEffect(reasoning, streaming) {
        if (streaming) innerScroll.scrollTo(innerScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ReasoningHeaderRow(
            title = title,
            duration = (durationMs ?: 0L).toDuration(DurationUnit.MILLISECONDS),
            loading = streaming,
            // 完成态给出「查看全文」的明确入口；流式期间头部即滚动预览，无需额外提示
            extra = if (streaming) null else {
                {
                    Text(
                        text = stringResource(R.string.photo_solve_view_reasoning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            },
            contentVisible = streaming,
            folded = false,
            onClick = onOpen,
        )
        if (streaming) {
            // 渐隐遮罩用页面背景色（预览直接铺在页面背景上，与聊天同款 DstIn 手法）
            val pageBg = MaterialTheme.colorScheme.surface
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = 0.99f }
                    .drawWithCache {
                        val fadePx = 28.dp.toPx()
                        val brush = Brush.verticalGradient(
                            startY = 0f,
                            endY = size.height,
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                (fadePx / size.height) to pageBg,
                                (1 - fadePx / size.height) to pageBg,
                                1.0f to Color.Transparent
                            )
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush = brush, size = Size(size.width, size.height), blendMode = BlendMode.DstIn)
                        }
                    }
                    .heightIn(max = REASONING_PREVIEW_HEIGHT)
                    .verticalScroll(innerScroll),
            ) {
                // 流式期间不启用 SelectionContainer，避免 selectable 并发修改崩溃（同聊天）
                MarkdownBlock(
                    content = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * 解题/结果态的题图摘要行（无卡壳）：小缩略图 + 状态。
 * 点击缩略图打开全屏缩放预览（ZoomableAsyncImage 自带）。
 * 行内不设「换题」按钮（根因：与底栏对话框条的「换一题」图标入口重复，
 * 双入口等权会增加底栏与行内两个语义相同的操作，底栏入口已足够且更顺手）。
 */
@Composable
private fun ImageThumbStrip(
    imageUri: String?,
    generating: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (imageUri != null) {
            val ctx = LocalContext.current
            val imageFile = remember(imageUri) {
                runCatching { uriToPrivateFile(ctx, imageUri) }.getOrNull()
            }
            // 自带点击全屏缩放预览（ImagePreviewDialog）
            ZoomableAsyncImage(
                model = imageFile?.absolutePath ?: imageUri,
                contentDescription = stringResource(R.string.photo_solve_tap_to_zoom),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 34.dp, height = 44.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.photo_solve_image_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    if (generating) R.string.photo_solve_solving else R.string.photo_solve_tap_to_zoom
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 解答区块（文档流版，无卡壳）：解题过程 + 精炼作答是同一份解答的两个层次——
 * 上部为详细推导（便于理解），底部为通铺浅色高亮的「作答 · 可直接誊抄」块。
 * 默认展开（生成中自动可见流式进度），可手动收起。
 * 折叠交互只挂在头部行的「展开/收起」上（重构根因：原整卡 clickable 折叠会
 * 拦截正文的长按拖选——用户想选字时被误折叠；作答复制也移入作答块头部行，
 * 恢复默认命中区，不再用 16dp 裸图标）。
 *
 * 折叠语义（重构根因：作答是"可直接誊抄"的最终答案，应随时可见）：
 * 「收起」只收起上方详细解题过程，作答块保持展示，不被折叠。
 */
@Composable
private fun SolutionCard(
    process: String,
    finalAnswer: String,
    generating: Boolean,
    scrollState: ScrollState,
    onCopyFinal: () -> Unit,
    containerWindowY: Float,
) {
    // 折叠对象是"解题过程"（processBlank 时没有可折叠内容，按钮不显示）
    val hasProcess = process.isNotBlank()
    var processExpanded by rememberSaveable { mutableStateOf(true) }
    // 「解答首块到达」的强制滚底锚：从等待/思考进入解答的首 chunk 到达时无条件
    // 滚底一次（用户此刻在等答案，理应看到答案开始出现），随后回归 nearBottom 跟随
    var outputPinned by remember { mutableStateOf(false) }
    // 直达作答：记录作答块在窗口中的 Y，配合滚动容器 Y 换算其内容偏移（见 jumpToAnswer）
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var answerBlockWindowY by remember { mutableFloatStateOf(0f) }
    val showJumpAnswer = hasProcess && finalAnswer.isNotBlank()
    fun jumpToAnswer() {
        val contentOffset = scrollState.value + (answerBlockWindowY - containerWindowY)
        val lead = with(density) { 8.dp.toPx() }
        scope.launch {
            scrollState.animateScrollTo((contentOffset - lead).coerceAtLeast(0f).roundToInt())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 头部行：区块语义「解答」在左，操作（直达作答 + 收起/展开 + 生成中折叠提示）在右
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.photo_solve_solution_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (hasProcess && generating && !processExpanded) {
                Text(
                    text = stringResource(R.string.photo_solve_generating_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (showJumpAnswer) {
                // 过程很长时一键直达底部作答块（无需翻完整段推导）
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    IconButton(onClick = ::jumpToAnswer, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = HugeIcons.ArrowDown01,
                            contentDescription = stringResource(R.string.photo_solve_jump_answer),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            if (hasProcess) {
                TextButton(onClick = { processExpanded = !processExpanded }) {
                    Text(
                        text = stringResource(
                            if (processExpanded) R.string.photo_solve_collapse
                            else R.string.photo_solve_expand
                        ),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // 详细解题过程：可收起；作答块始终展示在其下方（见函数注释的折叠语义）
        if (hasProcess && processExpanded) {
            // 正文（解题过程）：全宽无边框，可长按选择；不再被父级 clickable 拦截。
            // 流式生成期间不启用 SelectionContainer（每 chunk 重渲染 + selectable 并发
            // 修改有崩溃风险，同聊天 Reasoning 先例），生成结束内容定型后恢复可选中。
            val processContent: @Composable () -> Unit = {
                MarkdownBlock(
                    content = process,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (generating) processContent() else SelectionContainer { processContent() }
        }

        // 作答块：整行通铺浅色底，去圆角描边壳（卡中卡 -> 文档内的强调行）
        if (finalAnswer.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        answerBlockWindowY = coordinates.positionInWindow().y
                    }
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.photo_solve_final_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCopyFinal) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.copy),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                // 作答区：同解题过程——流式中不包 SelectionContainer（并发修改风险），
                // 生成结束后才可长按选择
                val finalContent: @Composable () -> Unit = {
                    MarkdownBlock(
                        content = finalAnswer,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (generating) finalContent() else SelectionContainer { finalContent() }
            }
        }
    }

    // 解答区块的流式跟随：key 含 finalAnswer（根因——此前不含：模型先流完 process
    // 再流 final 时，final 每 chunk 到达 LaunchedEffect 都不重启，作答增长不再贴底；
    // 同时缺「首块到达」锚点，从等待/思考区刚进入解答时页面停留在原处不滚动）。
    // 规则：解答首块（outputPinned）无条件贴底一次 → 其后用户位于底部附近才继续
    // 跟随（上翻阅读过程/思考时不被拉扯）。
    //
    // 为什么不用 animateScrollTo 跟随（含上次修复失效的根因）：
    // 1. LaunchedEffect 在重组提交后恢复、早于同帧 measure，此刻读取 maxValue 还是
    //    chunk 到达前的高度，直接滚动会停在"差一段"的位置；
    // 2. animateScrollTo 需要动画时间，流式 chunk 高频到达时动画不断被打断重来，
    //    永远滚不到最新底部——特别是生成进入尾声、内容最后一次大幅增高后不再有
    //    chunk 触发跟随，页面就停在半途，作答块始终差一截不可见。
    // 方案：跟随一律先等两帧让布局定稿（maxValue 含新 chunk），再 scrollTo 即时贴到
    // 最新底（与聊天思考弹窗 ReasoningSheetContent 的无动画贴底同一策略）；生成结束
    // （generating→false，内容高度已定型）时若用户仍在底部区域，补一次平滑收尾把
    // 完整作答块带进视口。
    val hasOutputNow = process.isNotBlank() || finalAnswer.isNotBlank()
    LaunchedEffect(hasOutputNow, generating) {
        if (hasOutputNow && generating) outputPinned = true
    }
    LaunchedEffect(processExpanded, process, finalAnswer, generating) {
        if (!processExpanded) return@LaunchedEffect
        if (generating) {
            val nearBottom = scrollState.maxValue - scrollState.value <
                FOLLOW_BOTTOM_THRESHOLD_PX
            if (outputPinned || nearBottom) {
                outputPinned = false
                // 等两帧：LaunchedEffect 恢复先于 measure，见上方根因注释
                withFrameNanos {}
                withFrameNanos {}
                scrollState.scrollTo(scrollState.maxValue)
            }
        } else {
            // 生成收尾：最后一段内容已定型、不再有新 chunk 触发跟随，
            // 用户停在底部区域（等答案语义）时把内容末端对齐视口底
            val nearBottom = scrollState.maxValue - scrollState.value <
                FOLLOW_BOTTOM_THRESHOLD_PX
            if (nearBottom) {
                withFrameNanos {}
                withFrameNanos {}
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
    }
}

/** 解析预览文件：file:// 直接转 File；content:// 交给 coil 的 uri 模型 */
private fun uriToPrivateFile(ctx: android.content.Context, uri: String): File? {
    if (!uri.startsWith("file:")) return null
    return File(uri.toUri().path ?: return null)
}

/**
 * 题干区块（文档流版，无卡壳）：展示「AI 读到的题面」——
 * vision 直送路径为模型 <problem_statement> 读题块，OCR 降级路径为回传题干文本；
 * 可修正后重新解题（vision 模型带图重读，OCR 模型纯文本重解）。
 * 头部行 = 「题干」标签 + 「修正」操作；正文可长按选择。
 * 视图/纠错闭环：识别错 → 点「修正」改文本 → 保存 → 重新解题。
 */
@Composable
private fun QuestionCard(
    question: String,
    generating: Boolean,
    onResolve: (String) -> Unit,
    onSolveAgain: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    // 展示/编辑统一走"去整段 latex 外壳"后的题干（根因见 unwrapWrappedLatexBlock：
    // 模型把整段题面包进 $$…$$/\[…\] 时按块级公式排版会毁掉 markdown 结构与正文可读性）；
    // 修正保存的也是干净文本，新一轮落库即不带外壳。
    val displayQuestion = remember(question) { unwrapWrappedLatexBlock(question) }
    // question 内容变化（新一轮生成回传修正文本）时同步草稿，避免编辑态内容过期
    var draft by remember(question, editing) { mutableStateOf(if (editing) displayQuestion else question) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.photo_solve_question_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!editing && !generating) {
                // 重新解题：与「修正」并列于题干卡纠错语境（底栏已让位给追问输入），
                // 低调图标，点击走 VM 的模型/状态守卫（模型未配置时给出配置引导提示）
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    IconButton(onClick = onSolveAgain, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = HugeIcons.RotateRight01,
                            contentDescription = stringResource(R.string.photo_solve_solve_again),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = { editing = true }) {
                    Text(
                        text = stringResource(R.string.photo_solve_edit_question),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        if (editing) {
            // 编辑输入框：保持细描边圆角底作为输入控件身份（正文区块已无卡壳，
            // 但文本输入需要可辨识边界，此边框属于控件而非卡片）
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 3,
                        maxLines = 10,
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.photo_solve_question_placeholder),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { editing = false }, enabled = !generating) {
                    Text(stringResource(R.string.photo_solve_edit_cancel))
                }
                Button(
                    onClick = {
                        editing = false
                        onResolve(draft)
                    },
                    enabled = !generating && draft.isNotBlank(),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Text(stringResource(R.string.photo_solve_question_save))
                }
            }
        } else {
            // 题干展示：与思考/解答同源渲染（markdown / LaTeX 公式 / 列表等，
            // 纯文本快速路径开销可忽略），便于数学题干中的公式正确呈现。
            // （重构根因：此前纯 Text 直出，OCR 回传的 LaTeX 原文不可读）
            // 流式生成期间不启用 SelectionContainer：题干卡每 chunk 重渲染，
            // selectable 文本高频更新可能触发并发修改崩溃（同聊天 Reasoning 的先例），
            // 生成结束内容定型后再恢复可选中。
            val content: @Composable () -> Unit = {
                MarkdownBlock(
                    content = displayQuestion,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (generating) content() else SelectionContainer { content() }
        }
    }
}

/**
 * 页内追问线程：挂在解答区块之后的多轮问答。
 * - 每轮 = 右侧浅色气泡的问题 + 思考行（弹窗查看全文）+ 全宽 Markdown 回答；
 * - 思考与回答都以 MarkdownBlock 渲染（markdown/latex）；生成中回答不包
 *   SelectionContainer，完成后可长按选择。
 */
@Composable
private fun FollowUpThread(
    turns: List<FollowUpTurn>,
    answering: Boolean,
    modifier: Modifier = Modifier,
) {
    var viewingReasoning by remember { mutableStateOf<FollowUpTurn?>(null) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        turns.forEachIndexed { index, turn ->
            val streaming = answering && index == turns.lastIndex
            // 用户问题：右偏气泡（左留 25% 间隙表达"右侧是我说的"）
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(0.25f))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(0.75f)
                ) {
                    Text(
                        text = turn.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }
            // 思考行：生成中显示"正在思考…"，有思考文本后可点击查看全文（markdown 渲染）
            val thinkingPending = streaming && turn.answer.isBlank()
            if (thinkingPending || turn.reasoning.isNotBlank()) {
                FollowUpThinkingRow(
                    streaming = thinkingPending,
                    reasoningAvailable = turn.reasoning.isNotBlank(),
                    onClick = {
                        if (turn.reasoning.isNotBlank()) viewingReasoning = turn
                    },
                )
            }
            // 模型回答：流式 / 已完成的选中态
            when {
                streaming -> {
                    MarkdownBlock(
                        content = turn.answer,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                turn.answer.isNotBlank() -> {
                    SelectionContainer {
                        MarkdownBlock(
                            content = turn.answer,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    // 追问思考全文弹窗（与解题思考同一套 Markdown 渲染）
    viewingReasoning?.takeIf { it.reasoning.isNotBlank() }?.let { turn ->
        ModalBottomSheet(onDismissRequest = { viewingReasoning = null }) {
            ReasoningSheetContent(
                reasoning = turn.reasoning,
                streaming = false,
                title = stringResource(R.string.photo_solve_reasoning_sheet_title),
                durationMs = 0L,
                onClose = { viewingReasoning = null },
            )
        }
    }
}

/** 追问的思考状态行：加载中提示 / 完成后入口（点击弹窗查看渲染后的思考全文） */
@Composable
private fun FollowUpThinkingRow(
    streaming: Boolean,
    reasoningAvailable: Boolean,
    onClick: () -> Unit,
) {
    val clickable = reasoningAvailable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = HugeIcons.Idea01,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (streaming) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.secondary
            }
        )
        Text(
            text = stringResource(
                if (streaming) R.string.photo_solve_follow_up_thinking
                else R.string.photo_solve_view_reasoning
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (streaming) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.secondary
            }
        )
    }
}

/**
 * 结果态底栏的「对话框式」常驻追问输入条。
 *
 * 形态根因（重构：原三按钮操作条 + 独立追问模式的二段式切换被合并为一个低姿态
 * 输入条——追问是结果页最高频动作，常驻输入框让「随时再问一句」零门槛；「换一题」
 * 从与追问等权的大按钮降级为行内低调图标，行内不再出现两个同等重要的主操作）。
 *
 * 行为对齐聊天输入：
 * - 未激活为单行输入框外观（placeholder 引导），点按聚焦 / 有草稿 / 有附图 /
 *   生成中时输入区展开为多行（最多 3 行）并出现发送（或停止）；
 * - 支持从相册附一张图（缩略图可移除，随本轮发送）；发送即清空输入与附图；
 * - 追问生成中发送按钮切换为停止（保留已生成的部分回答）。
 */
@Composable
private fun FollowUpDialogBar(
    draft: String,
    attachImageUri: String?,
    generating: Boolean,
    onDraftChange: (String) -> Unit,
    onAttachChange: (String?) -> Unit,
    onPickImage: () -> Unit,
    onSend: (String, String?) -> Unit,
    onStop: () -> Unit,
    onRetake: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // 展开态：正在编辑或有内容或正在回答——此时才显示附图/发送等完整控件，
    // 静止态只保留输入框 + 换一题，压低常驻存在感
    val active = focused || draft.isNotBlank() || attachImageUri != null || generating

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 已附图：缩略图 + 移除（允许发送前替换）
        if (attachImageUri != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AsyncImage(
                    model = attachImageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 44.dp, height = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp)
                        )
                )
                Spacer(modifier = Modifier.weight(1f))
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    IconButton(onClick = { onAttachChange(null) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.photo_solve_remove_attach),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 对话框主体：一整条低饱和底（surfaceVariant）承载 附图 + 输入 + 换一题，
        // 发送/停止在展开态出现在输入与换一题之间
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    // 附图入口常驻（低调）；追问生成中禁用
                    IconButton(
                        onClick = onPickImage,
                        enabled = !generating,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            HugeIcons.Image03,
                            contentDescription = stringResource(R.string.photo_solve_from_gallery),
                            tint = if (generating) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                // 多行输入：未激活单行、激活最多 3 行自动增高
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focused = it.isFocused }
                        .heightIn(min = 40.dp)
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (draft.isNotBlank() && !generating) onSend(draft, attachImageUri)
                        }
                    ),
                    maxLines = if (active) 3 else 1,
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty() && attachImageUri == null) {
                                Text(
                                    text = stringResource(R.string.photo_solve_follow_up_placeholder),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (active) 2 else 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                if (active) {
                    if (generating) {
                        // 追问生成中：发送切换为停止（保留已生成的部分回答）
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            IconButton(onClick = onStop, modifier = Modifier.size(38.dp)) {
                                Icon(
                                    HugeIcons.Cancel01,
                                    contentDescription = stringResource(R.string.photo_solve_cancel),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else {
                        val canSend = draft.isNotBlank()
                        Surface(
                            shape = CircleShape,
                            color = if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = HugeIcons.ArrowUp01,
                                    contentDescription = stringResource(R.string.send),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = canSend) {
                                            onSend(draft, attachImageUri)
                                        }
                                        .padding(9.dp)
                                )
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    // 「换一题」低调图标：常驻行尾；追问生成中禁用（避免中断进行中的回答）
                    IconButton(
                        onClick = onRetake,
                        enabled = !generating,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = HugeIcons.CameraRotated01,
                            contentDescription = stringResource(R.string.photo_solve_new_question),
                            tint = if (generating) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}
