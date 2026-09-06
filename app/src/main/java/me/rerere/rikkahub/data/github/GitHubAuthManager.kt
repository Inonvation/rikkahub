package me.rerere.rikkahub.data.github

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.secret.SecretStore
import me.rerere.rikkahub.utils.JsonInstant

private val Context.githubAuthDataStore by preferencesDataStore(name = "github_auth")

/** 绑定的账号展示信息（非机密）；token 密文随 blob 一起存 */
@Serializable
data class GitHubAccount(
    val login: String,
    val avatarUrl: String = "",
    val scopes: List<String> = emptyList(),
    val boundAt: Long = 0,
)

/** github_auth DataStore 的持久化单元。tokenCipher 由 [SecretStore]（Keystore AES-GCM）加密 */
@Serializable
internal data class GitHubAuthBlob(
    val tokenCipher: String,
    val account: GitHubAccount? = null,
    val invalid: Boolean = false,
)

/** 绑定状态。[invalid] 为 true 表示凭据已被 GitHub 侧判定失效（401），需重新绑定 */
data class GitHubAuthState(
    val account: GitHubAccount? = null,
    val invalid: Boolean = false,
    val loaded: Boolean = false,
)

/** 最近一次 GitHub API 响应的配额快照（X-RateLimit-* 头），设置页展示用 */
data class GitHubRateLimitSnapshot(
    val remaining: Int,
    val limit: Int,
    val resetEpochSec: Long,
)

/** Device Flow 的 UI 可见阶段 */
sealed interface DeviceFlowPhase {
    /** 展示 user_code 并引导用户打开 verificationUri */
    data class CodeReady(
        val userCode: String,
        val verificationUri: String,
        val expiresAtMillis: Long,
    ) : DeviceFlowPhase

    data class Success(val account: GitHubAccount) : DeviceFlowPhase

    data class Failed(val reason: String) : DeviceFlowPhase
}

/**
 * GitHub 账号绑定管理器。
 *
 * 存储设计（对齐主流 agent 实践）：
 * - token 只以 Keystore AES-GCM 密文落盘（对标 Claude Code 用系统 Keychain、gh 用系统
 *   credential store），明文仅存在于进程内存；
 * - 独立 DataStore 文件（github_auth），不进 Settings → 同步白名单天然隔离，不会上云；
 * - Keystore 密钥不随备份/换机迁移，密文换机后解不开即视为未绑定（token 低价值可重取）。
 *
 * 失效语义：GitHub OAuth token 无 refresh 流程，API 401 时置 invalid 并清内存 token，
 * UI 引导重新绑定；解绑只删本地凭据，撤销授权走 github.com/settings/connections 外链。
 */
class GitHubAuthManager(
    context: Context,
    private val settingsStore: SettingsStore,
    private val secretStore: SecretStore,
    private val oauthClient: GitHubOAuthClient,
) {
    companion object {
        private const val TAG = "GitHubAuthManager"
        private val KEY_BLOB = stringPreferencesKey("github_auth_blob")
    }

    private val dataStore = context.githubAuthDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(GitHubAuthState())
    val state: StateFlow<GitHubAuthState> = _state.asStateFlow()

    private val _rateLimit = MutableStateFlow<GitHubRateLimitSnapshot?>(null)
    val rateLimit: StateFlow<GitHubRateLimitSnapshot?> = _rateLimit.asStateFlow()

    /** 轮询期间网络失败正在自动重试（授权弹窗提示用，短暂为 true） */
    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedCipher: String? = null

    init {
        scope.launch { load() }
    }

    /** OAuth App 是否已配置（local.properties 的 GITHUB_CLIENT_ID）；未配置时隐藏绑定入口 */
    val isConfigured: Boolean
        get() = oauthClient.isConfigured()

    /** 当前可用 token（未绑定/失效返回 null）。供 GitHubSkillClient 与 shell 注入同步读取 */
    fun currentToken(): String? = cachedToken

    /** 需要在输出中脱敏的机密集合（当前仅一个绑定 token） */
    fun maskableSecrets(): Set<String> = setOfNotNull(cachedToken)

    fun onRateLimitHeaders(remaining: Int, limit: Int, resetEpochSec: Long) {
        _rateLimit.value = GitHubRateLimitSnapshot(remaining, limit, resetEpochSec)
    }

    /** GitHub API 401 回调：置失效并清内存 token，等待用户重新绑定 */
    fun onAuthInvalid() {
        val cipher = cachedCipher ?: return
        if (_state.value.invalid) return
        Log.w(TAG, "GitHub token rejected (401), mark invalid")
        cachedToken = null
        _state.value = _state.value.copy(invalid = true)
        scope.launch {
            persist(GitHubAuthBlob(tokenCipher = cipher, account = _state.value.account, invalid = true))
        }
    }

    /**
     * 走完整 Device Flow。[onPhase] 回调各阶段（调用方协程取消时轮询循环随之终止）。
     * 全程在 IO 线程执行。
     */
    suspend fun signInWithDeviceFlow(onPhase: (DeviceFlowPhase) -> Unit) = withContext(Dispatchers.IO) {
        if (!oauthClient.isConfigured()) {
            onPhase(DeviceFlowPhase.Failed("未配置 OAuth client_id（local.properties: GITHUB_CLIENT_ID）"))
            return@withContext
        }
        if (!secretStore.isAvailable) {
            onPhase(DeviceFlowPhase.Failed("安全存储不可用，无法保存凭据"))
            return@withContext
        }
        val start = runCatching { oauthClient.startDeviceCode() }.getOrElse { e ->
            onPhase(DeviceFlowPhase.Failed(e.message ?: "发起授权失败"))
            return@withContext
        }
        onPhase(
            DeviceFlowPhase.CodeReady(
                userCode = start.userCode,
                verificationUri = start.verificationUri,
                expiresAtMillis = System.currentTimeMillis() + start.expiresInSec * 1000,
            )
        )
        var interval = start.intervalSec
        var expiresAt = System.currentTimeMillis() + start.expiresInSec * 1000
        while (System.currentTimeMillis() < expiresAt) {
            delay(interval * 1000)
            val poll = runCatching { oauthClient.pollOnce(start.deviceCode) }.getOrElse {
                _retrying.value = true
                Log.w(TAG, "poll token failed, retry", it)
                continue
            }
            _retrying.value = false
            when (poll) {
                is GitHubOAuthClient.TokenPoll.Pending -> {}
                is GitHubOAuthClient.TokenPoll.SlowDown -> interval = poll.intervalSec
                is GitHubOAuthClient.TokenPoll.Success -> {
                    val profile = runCatching { oauthClient.fetchUser(poll.accessToken) }.getOrNull()
                    if (profile == null) {
                        onPhase(DeviceFlowPhase.Failed("获取账号信息失败（token 校验未通过）"))
                        return@withContext
                    }
                    val error = activate(poll.accessToken, profile)
                    if (error != null) {
                        onPhase(DeviceFlowPhase.Failed(error))
                    } else {
                        onPhase(DeviceFlowPhase.Success(_state.value.account!!))
                    }
                    return@withContext
                }
                is GitHubOAuthClient.TokenPoll.Denied -> {
                    onPhase(DeviceFlowPhase.Failed("你取消了授权"))
                    return@withContext
                }
                is GitHubOAuthClient.TokenPoll.Expired -> {
                    onPhase(DeviceFlowPhase.Failed("授权码已过期，请重新发起"))
                    return@withContext
                }
                is GitHubOAuthClient.TokenPoll.Error -> {
                    onPhase(DeviceFlowPhase.Failed(poll.message))
                    return@withContext
                }
            }
        }
        onPhase(DeviceFlowPhase.Failed("授权超时，请重试"))
    }

    /** PAT 兜底绑定：校验（GET /user）→ 加密落盘。返回错误信息，null 表示成功 */
    suspend fun bindWithPat(rawToken: String): String? = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        if (token.isEmpty()) return@withContext "请输入 token"
        if (!secretStore.isAvailable) return@withContext "安全存储不可用，无法保存凭据"
        val profile = runCatching { oauthClient.fetchUser(token) }.getOrNull()
            ?: return@withContext "token 无效或网络失败（GET /user 未通过）"
        val error = activate(token, profile)
        error
    }

    /** 解绑：清本地凭据（GitHub 侧授权需用户自行到 settings/connections 撤销） */
    suspend fun unbind() {
        cachedToken = null
        cachedCipher = null
        _state.value = GitHubAuthState(loaded = true)
        persist(null)
        settingsStore.update { it.copy(githubAccount = null) }
    }

    /** 校验通过后激活：加密 → 内存缓存 → 落盘 → 更新状态。返回错误信息或 null */
    private suspend fun activate(token: String, profile: GitHubOAuthClient.GitHubUserProfile): String? {
        val cipher = secretStore.encrypt(token)
            ?: return "安全存储不可用，无法保存凭据"
        val account = GitHubAccount(
            login = profile.login,
            avatarUrl = profile.avatarUrl,
            scopes = profile.scopes,
            boundAt = System.currentTimeMillis(),
        )
        cachedToken = token
        cachedCipher = cipher
        _state.value = GitHubAuthState(account = account, invalid = false, loaded = true)
        persist(GitHubAuthBlob(tokenCipher = cipher, account = account, invalid = false))
        // 账号元数据镜像进 Settings（不含 token）：进同步白名单，换机恢复后可引导重绑
        settingsStore.update { it.copy(githubAccount = account) }
        return null
    }

    private suspend fun load() {
        val json = dataStore.data.first()[KEY_BLOB]
        if (json == null) {
            _state.value = GitHubAuthState(loaded = true)
            return
        }
        val blob = runCatching { JsonInstant.decodeFromString<GitHubAuthBlob>(json) }.getOrNull()
        if (blob == null) {
            Log.w(TAG, "github auth blob corrupted, treat as unbound")
            _state.value = GitHubAuthState(loaded = true)
            return
        }
        val token = if (blob.invalid) null else secretStore.decrypt(blob.tokenCipher)
        cachedCipher = blob.tokenCipher
        cachedToken = token
        // 解密失败 = Keystore 密钥不可用（换机恢复/系统重置），等价于已失效
        _state.value = GitHubAuthState(
            account = blob.account,
            invalid = blob.invalid || (token == null && blob.account != null),
            loaded = true,
        )
    }

    private suspend fun persist(blob: GitHubAuthBlob?) {
        runCatching {
            dataStore.edit { preferences ->
                if (blob == null) {
                    preferences.remove(KEY_BLOB)
                } else {
                    preferences[KEY_BLOB] = JsonInstant.encodeToString(blob)
                }
            }
        }.onFailure { e -> Log.w(TAG, "persist github auth failed", e) }
    }
}
