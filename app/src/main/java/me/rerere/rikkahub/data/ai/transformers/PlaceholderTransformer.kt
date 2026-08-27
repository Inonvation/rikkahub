package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.Temporal
import java.util.Locale
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.toJavaInstant

data class PlaceholderCtx(
    val context: Context,
    val settingsStore: SettingsStore,
    val model: Model,
    val assistant: Assistant,
    /** 本条消息的解析基准时刻（取消息自身 createdAt），时间类占位符据此渲染，保证历史字节稳定 */
    val moment: LocalDateTime = LocalDateTime.now(),
)

interface PlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo>
}

data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
    val resolver: (PlaceholderCtx) -> String,
    /** true = 实时波动值（电量等）：仅替换最后一条用户消息，其余位置删除，保护 prompt 缓存前缀 */
    val volatile: Boolean = false,
)

class PlaceholderBuilder {
    private val placeholders = mutableMapOf<String, PlaceholderInfo>()

    fun placeholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver)
    }

    fun volatilePlaceholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver, volatile = true)
    }

    fun build(): Map<String, PlaceholderInfo> = placeholders.toMap()
}

fun buildPlaceholders(block: PlaceholderBuilder.() -> Unit): Map<String, PlaceholderInfo> {
    return PlaceholderBuilder().apply(block).build()
}

object DefaultPlaceholderProvider : PlaceholderProvider {
    override val placeholders: Map<String, PlaceholderInfo> = buildPlaceholders {
        placeholder("cur_date", { Text(stringResource(R.string.placeholder_current_date)) }) {
            it.moment.toDateString()
        }

        placeholder("model_id", { Text(stringResource(R.string.placeholder_model_id)) }) {
            it.model.modelId
        }

        placeholder("model_name", { Text(stringResource(R.string.placeholder_model_name)) }) {
            it.model.displayName
        }

        placeholder("locale", { Text(stringResource(R.string.placeholder_locale)) }) {
            Locale.getDefault().displayName
        }

        placeholder("timezone", { Text(stringResource(R.string.placeholder_timezone)) }) {
            java.util.TimeZone.getDefault().displayName
        }

        placeholder("system_version", { Text(stringResource(R.string.placeholder_system_version)) }) {
            "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
        }

        placeholder("device_info", { Text(stringResource(R.string.placeholder_device_info)) }) {
            "${Build.BRAND} ${Build.MODEL}"
        }

        // 电量是逐请求波动的实时值：只在最后一条用户消息处解析，其余位置删除
        volatilePlaceholder("battery_level", { Text(stringResource(R.string.placeholder_battery_level)) }) {
            it.context.batteryLevel().toString()
        }

        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }

        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) {
            it.assistant.name.ifBlank { "assistant" }
        }

        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }
    }

    private fun Temporal.toDateString() = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Context.batteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}

/** 逐 key 做字面替换：先完整形式 {{key}} 再短形式 {key}，均忽略大小写（与历史行为一致） */
internal fun replacePlaceholderToken(text: String, key: String, value: String): String =
    text
        .replace(oldValue = "{{$key}}", newValue = value, ignoreCase = true)
        .replace(oldValue = "{$key}", newValue = value, ignoreCase = true)

/**
 * 纯函数渲染核心：
 * - [stableValues] 在所有消息上无差别解析；
 * - [volatileValues] 仅当 [includeVolatile]（目标=最后一条用户消息）为 true 时解析，
 *   否则把对应 token 删除——避免波动值进入缓存前缀。
 */
internal fun renderPlaceholders(
    text: String,
    stableValues: Map<String, String>,
    volatileValues: Map<String, String> = emptyMap(),
    includeVolatile: Boolean = false,
): String {
    var result = text
    stableValues.forEach { (key, value) -> result = replacePlaceholderToken(result, key, value) }
    volatileValues.forEach { (key, value) ->
        result = if (includeVolatile) {
            replacePlaceholderToken(result, key, value)
        } else {
            replacePlaceholderToken(result, key, "")
        }
    }
    return result
}

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {
    private val defaultProvider = DefaultPlaceholderProvider

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settingsStore = get<SettingsStore>()
        val tz = TimeZone.currentSystemDefault()
        // volatile 占位符唯一合法位置：最后一条非合成用户消息
        val tailUserIndex = messages.indexOfLast {
            it.role == MessageRole.USER && !it.isSynthetic
        }
        return messages.mapIndexed { index, message ->
            // 按本条消息自身 createdAt 解析时间类占位符：同一请求内历史渲染结果逐字节稳定
            val moment = message.createdAt.toRequestMoment(tz)
            val placeholderCtx = PlaceholderCtx(
                context = ctx.context,
                settingsStore = settingsStore,
                model = ctx.model,
                assistant = ctx.assistant,
                moment = moment,
            )
            message.copy(
                parts = message.parts.map { part ->
                    if (part !is UIMessagePart.Text) {
                        part
                    } else {
                        val stableValues = defaultProvider.placeholders
                            .filterValues { !it.volatile }
                            .mapValues { (_, info) -> info.resolver(placeholderCtx) }
                        val volatileValues = defaultProvider.placeholders
                            .filterValues { it.volatile }
                            .mapValues { (_, info) -> info.resolver(placeholderCtx) }
                        part.copy(
                            text = renderPlaceholders(
                                text = part.text,
                                stableValues = stableValues,
                                volatileValues = volatileValues,
                                includeVolatile = index == tailUserIndex,
                            )
                        )
                    }
                }
            )
        }
    }
}

private fun kotlinx.datetime.LocalDateTime.toRequestMoment(tz: TimeZone): LocalDateTime =
    LocalDateTime.ofInstant(toInstant(tz).toJavaInstant(), ZoneId.systemDefault())
