package me.rerere.rikkahub

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.dokar.sonner.Toaster
import com.dokar.sonner.rememberToasterState
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker
import me.rerere.rikkahub.data.db.MigrationState
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.ui.activity.SafeModeActivity
import me.rerere.rikkahub.ui.components.ui.TTSController
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTabletAdaptation
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import me.rerere.rikkahub.ui.pages.assistant.AssistantPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantIdentityPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantModelPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantExtensionsPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantLocalToolPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMcpPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantToolsPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantMemoryPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantPromptPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantRequestPage
import me.rerere.rikkahub.ui.pages.backup.BackupPage
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.rikkahub.ui.pages.chat.GroupDiscussionCreatePage
import me.rerere.rikkahub.ui.pages.chat.GroupDiscussionEditPage
import me.rerere.rikkahub.ui.pages.chat.GroupDiscussionListPage
import me.rerere.rikkahub.ui.pages.chat.GroupDiscussionPage
import me.rerere.rikkahub.ui.pages.chat.GroupDetailPage
import me.rerere.rikkahub.ui.pages.debug.DebugPage
import me.rerere.rikkahub.ui.pages.extensions.PromptPage
import me.rerere.rikkahub.ui.pages.extensions.QuickMessagesPage
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillDetailPage
import me.rerere.rikkahub.ui.pages.extensions.skills.SkillsPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspacePage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFileEditorPage
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalPage
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.rikkahub.ui.pages.favorite.FavoritePage
import me.rerere.rikkahub.ui.pages.history.HistoryPage
import me.rerere.rikkahub.ui.pages.imggen.ImageGenPage
import me.rerere.rikkahub.ui.pages.log.LogPage
import me.rerere.rikkahub.ui.pages.search.SearchPage
import me.rerere.rikkahub.ui.pages.chat.SubAgentPanelPage
import me.rerere.rikkahub.ui.pages.chat.SubAgentDetailPage
import me.rerere.rikkahub.ui.pages.setting.AgentConfigFilePage
import me.rerere.rikkahub.ui.pages.setting.SettingAboutPage
import me.rerere.rikkahub.ui.pages.setting.SettingAgentActionPage
import me.rerere.rikkahub.ui.pages.setting.SettingAppearancePage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesNotificationPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesGeneralPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesNetworkPage
import me.rerere.rikkahub.ui.pages.setting.SettingStudyToolsPage
import me.rerere.rikkahub.ui.pages.recyclebin.RecycleBinPage
import me.rerere.rikkahub.ui.pages.setting.SettingPreferencesUIPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayGroupPage
import me.rerere.rikkahub.ui.pages.setting.SettingExtensionsPage
import me.rerere.rikkahub.ui.pages.setting.SettingThemePage
import me.rerere.rikkahub.ui.pages.setting.SettingDonatePage
import me.rerere.rikkahub.ui.pages.setting.SettingFilesPage
import me.rerere.rikkahub.ui.pages.setting.SettingMcpPage
import me.rerere.rikkahub.ui.pages.setting.ManagementPage
import me.rerere.rikkahub.ui.pages.setting.SettingModelPage
import me.rerere.rikkahub.ui.pages.setting.SettingModePage
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingSpeechPage
import me.rerere.rikkahub.ui.pages.setting.SettingWebPage
import me.rerere.rikkahub.ui.pages.setting.SettingDevicePage
import me.rerere.rikkahub.ui.pages.setting.SettingDeviceWhitelistPage
import me.rerere.rikkahub.ui.pages.setting.SettingDevicePermissionPage
import me.rerere.rikkahub.ui.pages.setting.SettingDeviceAuditPage
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage
import me.rerere.rikkahub.ui.pages.stats.StatsPage
import me.rerere.rikkahub.ui.pages.study.stats.StudyStatsPage
import me.rerere.rikkahub.ui.pages.knowledge.KnowledgeBasesPage
import me.rerere.rikkahub.ui.pages.knowledge.KnowledgeBaseDetailPage
import me.rerere.rikkahub.ui.pages.knowledge.KnowledgeBaseSettingsPage
import me.rerere.rikkahub.ui.pages.study.vocabulary.VocabularyPanelPage
import me.rerere.rikkahub.ui.pages.study.notes.NotesPanelPage
import me.rerere.rikkahub.ui.pages.study.wrongquestions.WrongQuestionPanelPage
import me.rerere.rikkahub.ui.pages.study.knowledgecards.KnowledgeCardPanelPage
import me.rerere.rikkahub.ui.pages.translator.TranslatorPage
import me.rerere.rikkahub.ui.pages.trustedfolders.TrustedFolderDetailPage
import me.rerere.rikkahub.ui.pages.trustedfolders.TrustedFolderFileEditorPage
import me.rerere.rikkahub.ui.pages.trustedfolders.TrustedFolderSettingsPage
import me.rerere.rikkahub.ui.pages.trustedfolders.TrustedFoldersPage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.openUsageAccessSettings
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"

class RouteActivity : ComponentActivity() {
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private var navStack: MutableList<NavKey>? = null

    // Volume key listener registry — last registered handler wins
    internal val volumeKeyListeners = mutableListOf<(isVolumeUp: Boolean) -> Boolean>()

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeUp = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> true
                KeyEvent.KEYCODE_VOLUME_DOWN -> false
                else -> return super.dispatchKeyEvent(event)
            }
            if (volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        if (CrashHandler.hasCrashed(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        setContent {
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .components {
                            add(
                                OkHttpNetworkFetcherFactory(
                                    callFactory = { okHttpClient },
                                    cacheStrategy = { CacheControlCacheStrategy() },
                                )
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                add(AnimatedImageDecoder.Factory())
                            } else {
                                add(GifDecoder.Factory())
                            }
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes()
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    @Composable
    private fun ShareHandler(backStack: MutableList<NavKey>) {
        val shareIntent = remember {
            Intent().apply {
                action = intent?.action
                putExtra(Intent.EXTRA_TEXT, intent?.getStringExtra(Intent.EXTRA_TEXT))
                putExtra(Intent.EXTRA_STREAM, intent?.getStringExtra(Intent.EXTRA_STREAM))
                putExtra(Intent.EXTRA_PROCESS_TEXT, intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
            }
        }

        LaunchedEffect(backStack) {
            when (shareIntent.action) {
                Intent.ACTION_SEND -> {
                    val text = shareIntent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                    val imageUri = shareIntent.getStringExtra(Intent.EXTRA_STREAM)
                    backStack.add(Screen.ShareHandler(text, imageUri))
                }

                Intent.ACTION_PROCESS_TEXT -> {
                    val text = shareIntent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
                    backStack.add(Screen.ShareHandler(text, null))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Navigate to the chat screen if a conversation ID is provided
        intent.getStringExtra("conversationId")?.let { text ->
            navStack?.add(Screen.Chat(text))
        }
        // Activity 存活期间再次以 SEND / PROCESS_TEXT 启动（例如从最近任务再次分享）时，
        // ShareHandler 组件的 remember 只捕获首次 intent，这里必须直接入栈，否则新分享内容被吞。
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val imageUri = intent.getStringExtra(Intent.EXTRA_STREAM)
                navStack?.add(Screen.ShareHandler(text, imageUri))
            }

            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: ""
                navStack?.add(Screen.ShareHandler(text, null))
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    fun AppRoutes() {
        val toastState = rememberToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        val asr = rememberCustomAsrState()
        val eventBus = koinInject<AppEventBus>()
        LaunchedEffect(tts) {
            eventBus.events.collect { event ->
                when (event) {
                    is AppEvent.Speak -> tts.speak(event.text)
                    is AppEvent.OpenUsageAccessSettings -> this@RouteActivity.openUsageAccessSettings()
                    is AppEvent.McpOAuthCallback -> Unit // 由 McpManager 消费
                    is AppEvent.ChatGenerationStarted -> Unit // 由 ChatPage 消费
                    is AppEvent.ChatGenerationUpdate -> Unit // 由 ChatNotificationManager 消费
                    is AppEvent.ChatGenerationEnded -> Unit // 由 ChatNotificationManager 消费
                }
            }
        }
        val migrationState by DatabaseMigrationTracker.state.collectAsStateWithLifecycle()

        // DataStore 两阶段加载：settingsFlow 先发 dummy(init=true) 再发真实值。若 startScreen 在
        // settings 就绪前就用 settings 值计算，真实值到达时 remember key 翻转，rememberNavBackStack 的
        // rememberSaveable 会把整个 backStack 重置（退后台恢复丢页面/意外跳会话的根因）。
        // 因此 settings 就绪前先显示启动占位，就绪后一次性确定 startScreen（remember(Unit) 不翻转）。
        if (settings.init) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@AppRoutes
        }

        // remember：避免每次重组都同步读 SharedPreferences + 生成 UUID（主线程磁盘读）
        // 冷启动 / 进程被杀后重建：按 createNewConversationOnStart 决定新建会话或恢复上次会话。
        // 短暂退后台（进程活着）不走这里——导航栈由 rememberNavBackStack 的 saved state 保留原界面。
        val startScreen = remember(Unit) {
            Screen.Chat(
                id = if (settings.displaySetting.createNewConversationOnStart) {
                    Uuid.random().toString()
                } else {
                    readStringPreference(
                        "lastConversationId",
                        Uuid.random().toString()
                    ) ?: Uuid.random().toString()
                }
            )
        }

        val backStack = rememberNavBackStack(startScreen)
        SideEffect { this@RouteActivity.navStack = backStack }

        ShareHandler(backStack)

        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides Navigator(backStack),
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalTabletAdaptation provides settings.displaySetting.enableTabletAdaptation,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
                LocalASRState provides asr,
            ) {
                Toaster(
                    state = toastState,
                    darkTheme = LocalDarkMode.current,
                    richColors = true,
                    alignment = Alignment.TopCenter,
                    showCloseButton = true,
                )
                TTSController()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { testTagsAsResourceId = true }
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    NavDisplay(
                        backStack = backStack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        modifier = Modifier.fillMaxSize(),
                        onBack = { backStack.removeLastOrNull() },
                        transitionSpec = {
                            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
                            else {
                                slideInHorizontally { it } togetherWith
                                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                            }
                        },
                        popTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        predictivePopTransitionSpec = {
                            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                                slideOutHorizontally { it }
                        },
                        entryProvider = entryProvider {
                            entry<Screen.Chat>(
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                ChatPage(
                                    id = Uuid.parse(key.id),
                                    text = key.text,
                                    files = key.files.map { it.toUri() },
                                    nodeId = key.nodeId?.let { Uuid.parse(it) },
                                    mode = key.mode,
                                )
                            }

                            entry<Screen.ShareHandler> { key ->
                                ShareHandlerPage(
                                    text = key.text,
                                    image = key.streamUri
                                )
                            }

                            entry<Screen.History> {
                                HistoryPage()
                            }

                            entry<Screen.Favorite> {
                                FavoritePage()
                            }

                            entry<Screen.Assistant> {
                                AssistantPage()
                            }

                            entry<Screen.AssistantDetail> { key ->
                                AssistantDetailPage(key.id)
                            }

                            entry<Screen.GroupDiscussionCreate> {
                                GroupDiscussionCreatePage()
                            }

                            entry<Screen.GroupDiscussionList> {
                                GroupDiscussionListPage()
                            }

                            entry<Screen.GroupDetail> { key ->
                                GroupDetailPage(id = key.id)
                            }

                            entry<Screen.GroupDiscussionEdit> { key ->
                                GroupDiscussionEditPage(id = key.id)
                            }

                            entry<Screen.GroupDiscussion> { key ->
                                GroupDiscussionPage(id = key.id)
                            }

                            entry<Screen.AssistantIdentity> { key ->
                                AssistantIdentityPage(key.id)
                            }

                            entry<Screen.AssistantModel> { key ->
                                AssistantModelPage(key.id)
                            }

                            entry<Screen.AssistantPrompt> { key ->
                                AssistantPromptPage(key.id)
                            }

                            entry<Screen.AssistantMemory> { key ->
                                AssistantMemoryPage(key.id)
                            }

                            entry<Screen.AssistantRequest> { key ->
                                AssistantRequestPage(key.id)
                            }

                            entry<Screen.AssistantMcp> { key ->
                                AssistantMcpPage(key.id)
                            }

                            entry<Screen.AssistantTools> { key ->
                                AssistantToolsPage(key.id)
                            }

                            entry<Screen.AssistantLocalTool> { key ->
                                AssistantLocalToolPage(key.id)
                            }

                            entry<Screen.AssistantInjections> { key ->
                                AssistantExtensionsPage(key.id)
                            }

                            entry<Screen.Translator> {
                                TranslatorPage()
                            }

                            entry<Screen.Setting> {
                                SettingPage()
                            }

                            entry<Screen.Backup> {
                                BackupPage()
                            }

                            entry<Screen.ImageGen> {
                                ImageGenPage()
                            }

                            entry<Screen.WebView> { key ->
                                WebViewPage(key.url, key.contentId)
                            }

                            entry<Screen.SettingTheme> {
                                SettingThemePage()
                            }

                            entry<Screen.SettingAppearance> {
                                SettingAppearancePage()
                            }

                            entry<Screen.SettingPreferencesNotification> {
                                SettingPreferencesNotificationPage()
                            }

                            entry<Screen.SettingPreferencesGeneral> {
                                SettingPreferencesGeneralPage()
                            }

                            entry<Screen.SettingStudyTools> {
                                SettingStudyToolsPage()
                            }

                            entry<Screen.RecycleBin> {
                                RecycleBinPage()
                            }

                            entry<Screen.SettingPreferencesUI> {
                                SettingPreferencesUIPage()
                            }

                            entry<Screen.SettingDisplayGroup> { key ->
                                SettingDisplayGroupPage(key.group)
                            }

                            entry<Screen.SettingPreferencesNetwork> {
                                SettingPreferencesNetworkPage()
                            }

                            entry<Screen.SettingExtensions> {
                                SettingExtensionsPage()
                            }

                            entry<Screen.SettingAgentAction> {
                                SettingAgentActionPage()
                            }

                            entry<Screen.AgentConfigFile> { key ->
                                AgentConfigFilePage(
                                    path = key.path,
                                    title = key.title,
                                )
                            }

                            entry<Screen.SettingProvider> {
                                SettingProviderPage()
                            }

                            entry<Screen.ManagementDashboard> {
                                ManagementPage()
                            }

                            entry<Screen.SettingProviderDetail> { key ->
                                val id = Uuid.parse(key.providerId)
                                SettingProviderDetailPage(id = id)
                            }

                            entry<Screen.SettingModels> {
                                SettingModelPage()
                            }

                            entry<Screen.SettingModes> {
                                SettingModePage()
                            }

                            entry<Screen.SettingAbout> {
                                SettingAboutPage()
                            }

                            entry<Screen.SettingSearch> {
                                SettingSearchPage()
                            }

                            entry<Screen.SettingSearchDetail> { key ->
                                val id = Uuid.parse(key.serviceId)
                                SettingSearchDetailPage(id)
                            }

                            entry<Screen.SettingSpeech> {
                                SettingSpeechPage()
                            }

                            entry<Screen.SettingMcp> {
                                SettingMcpPage()
                            }

                            entry<Screen.SettingDonate> {
                                SettingDonatePage()
                            }

                            entry<Screen.SettingFiles> {
                                SettingFilesPage()
                            }

                            entry<Screen.SettingDeviceAudit> {
                                SettingDeviceAuditPage()
                            }

                            entry<Screen.SettingDevicePermission> {
                                SettingDevicePermissionPage()
                            }

                            entry<Screen.SettingDeviceWhitelist> {
                                SettingDeviceWhitelistPage()
                            }

                            entry<Screen.SettingDevice> {
                                SettingDevicePage()
                            }

                            entry<Screen.SettingWeb> {
                                SettingWebPage()
                            }

                            entry<Screen.Debug> {
                                DebugPage()
                            }

                            entry<Screen.Log> {
                                LogPage()
                            }


                            entry<Screen.QuickMessages> {
                                QuickMessagesPage()
                            }

                            entry<Screen.Prompts> {
                                PromptPage()
                            }

                            entry<Screen.Skills> {
                                SkillsPage()
                            }

                            entry<Screen.Workspaces> {
                                WorkspacePage()
                            }

                            entry<Screen.WorkspaceDetail> { key ->
                                WorkspaceDetailPage(
                                    id = key.id,
                                    initialArea = key.area,
                                    initialPath = key.path,
                                )
                            }

                            entry<Screen.WorkspaceTerminal> { key ->
                                WorkspaceTerminalPage(key.id)
                            }

                            entry<Screen.WorkspaceFileEditor> { key ->
                                WorkspaceFileEditorPage(
                                    id = key.id,
                                    area = WorkspaceStorageArea.valueOf(key.area),
                                    path = key.path,
                                )
                            }

                            entry<Screen.SkillDetail> { key ->
                                SkillDetailPage(skillName = key.skillName)
                            }

                            entry<Screen.MessageSearch> {
                                SearchPage()
                            }

                            entry<Screen.SubAgentPanel> { key ->
                                SubAgentPanelPage(key.id)
                            }

                            entry<Screen.SubAgentDetail> { key ->
                                SubAgentDetailPage(key.id, key.conversationId)
                            }

                            entry<Screen.Stats> {
                                StatsPage()
                            }

                            entry<Screen.StudyStats> {
                                StudyStatsPage()
                            }

                            entry<Screen.KnowledgeBases> {
                                KnowledgeBasesPage()
                            }

                            entry<Screen.KnowledgeBaseDetail> { key ->
                                KnowledgeBaseDetailPage(key.id)
                            }

                            entry<Screen.KnowledgeBaseSettings> { key ->
                                KnowledgeBaseSettingsPage(key.id)
                            }

                            entry<Screen.VocabularyPanel> {
                                VocabularyPanelPage()
                            }

                            entry<Screen.NotesPanel> {
                                NotesPanelPage()
                            }

                            entry<Screen.WrongQuestionPanel> {
                                WrongQuestionPanelPage()
                            }

                            entry<Screen.KnowledgeCardPanel> {
                                KnowledgeCardPanelPage()
                            }

                            entry<Screen.TrustedFolders> {
                                TrustedFoldersPage()
                            }

                            entry<Screen.TrustedFolderDetail> { key ->
                                TrustedFolderDetailPage(projectId = key.projectId, initialPath = key.path)
                            }

                            entry<Screen.TrustedFolderEditor>(
                                // 编辑器页面用轻量 fade 转场：跳转时目标页需加载/渲染笔记，
                                // 避免 slide+scale 重动画与渲染争抢主线程导致转场卡顿
                                metadata = NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() }
                                    + NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
                            ) { key ->
                                TrustedFolderFileEditorPage(
                                    projectId = key.projectId,
                                    path = key.path,
                                    dest = key.dest,
                                )
                            }

                            entry<Screen.TrustedFolderSettings> { key ->
                                TrustedFolderSettingsPage(projectId = key.projectId)
                            }
                        }
                    )
                    if (BuildConfig.DEBUG) {
                        Text(
                            text = "[开发模式]",
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                    AnimatedVisibility(
                        visible = migrationState is MigrationState.Migrating,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val state = migrationState as? MigrationState.Migrating
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = stringResource(R.string.db_migrating),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (state != null) {
                                    Text(
                                        text = "v${state.from} → v${state.to}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

sealed interface Screen : NavKey {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null,
        val mode: String? = null,
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data object GroupDiscussionCreate : Screen

    @Serializable
    data object GroupDiscussionList : Screen

    /** 群主页。id = groupId（群组本身，含 config） */
    @Serializable
    data class GroupDetail(val id: String) : Screen

    /** 群组编辑页。id = groupId（注意与 GroupDiscussion 的 id 语义不同，勿混用） */
    @Serializable
    data class GroupDiscussionEdit(val id: String) : Screen

    /** 群组讨论页。id = conversationId（群组内单场会话）；群组配置经 conversation.groupId 读取 */
    @Serializable
    data class GroupDiscussion(val id: String) : Screen

    @Serializable
    data class AssistantIdentity(val id: String) : Screen

    @Serializable
    data class AssistantModel(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    
    @Serializable
    data class AssistantTools(val id: String) : Screen
@Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object ManagementDashboard : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingAppearance : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingStudyTools : Screen

    @Serializable
    data object RecycleBin : Screen

    @Serializable
    data object StudyStats : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data class SettingDisplayGroup(val group: String) : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingExtensions : Screen

    @Serializable
    data object SettingAgentAction : Screen

    @Serializable
    data class AgentConfigFile(
        /** 相对 agent/ 的配置文件路径（白名单内） */
        val path: String,
        /** 顶栏展示名；null 时回退为文件名 */
        val title: String? = null,
    ) : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingModes : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingDonate : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object SettingDevice : Screen

    @Serializable
    data object SettingDeviceWhitelist : Screen

    @Serializable
    data object SettingDevicePermission : Screen

    @Serializable
    data object SettingDeviceAudit : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(
        val id: String,
        /** 初始存储区（"FILES"/"LINUX"），用于从聊天跳转到文件所在目录；为空则用默认 FILES */
        val area: String? = null,
        /** 初始目录路径（相对存储区根，空串=根目录），用于定位到文件所在目录 */
        val path: String? = null,
    ) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data class SubAgentPanel(val id: String) : Screen

    @Serializable
    data class SubAgentDetail(val id: String, val conversationId: String? = null) : Screen

    @Serializable
    data object Stats : Screen

    @Serializable
    data object KnowledgeBases : Screen

    @Serializable
    data class KnowledgeBaseDetail(val id: String) : Screen

    @Serializable
    data class KnowledgeBaseSettings(val id: String) : Screen

    @Serializable
    data object VocabularyPanel : Screen

    @Serializable
    data object NotesPanel : Screen

    @Serializable
    data object WrongQuestionPanel : Screen

    @Serializable
    data object KnowledgeCardPanel : Screen

    @Serializable
    data object TrustedFolders : Screen

    @Serializable
    data class TrustedFolderDetail(
        val projectId: String,
        /** 初始目录路径（相对项目根，空串=根目录），用于从入口跳转定位 */
        val path: String = "",
    ) : Screen

    @Serializable
    data class TrustedFolderEditor(
        /** 所属项目 id（编辑器按项目操作，与激活项目无关） */
        val projectId: String,
        /** 相对项目根的完整文件路径 */
        val path: String,
        /** 双链目标（笔记名）。非空时进入页面先解析成 path 再加载，避免跳转前等索引构建卡顿 */
        val dest: String? = null,
    ) : Screen

    @Serializable
    data class TrustedFolderSettings(
        /** 目标项目 id，设置只对该项目生效 */
        val projectId: String,
    ) : Screen
}
