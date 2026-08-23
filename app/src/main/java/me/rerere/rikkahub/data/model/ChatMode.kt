package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.BuiltInTools
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

/**
 * 鍔╂墜鑳藉姏妯″紡锛氬喅瀹氱敓鎴愭椂娉ㄥ叆鍝簺宸ュ叿鏃忋€佺郴缁熸彁绀鸿瘝鐗囨涓庣幆澧冭鏄庛€?
 *
 * 涓庢棦鏈夈€屾ā寮忔敞鍏ャ€嶏紙[PromptInjection.ModeInjection]锛屾彁绀鸿瘝娉ㄥ叆绫诲埆锛夎涔夐殧绂伙紝浜掍笉骞叉壈銆?
 *
 * 妯″紡涓哄彲缁勫悎缁撴瀯锛氬唴缃ā寮忓搴斾竴缁?[Capability] 娓呭崟锛岀鐞嗘ā寮忓彲閫氳繃
 * [CustomModeConfig] 澹版槑涓€浠借兘鍔涙竻鍗曠敓鎴愭柊妯″紡锛堝啓鎿嶄綔闇€鐢ㄦ埛瀹℃壒锛夈€?
 */
@Serializable
enum class ChatMode {
    /** 鏋佺畝锛氬彧娉ㄥ叆鐢ㄦ埛鑷畾涔夋彁绀鸿瘝锛屼繚鐣欐湰鍦板伐鍏枫€佽仈缃戞悳绱笌闄勪欢瑙ｆ瀽锛堜笉娉ㄥ叆 MCP/澶栭儴宸ュ叿澹版槑锛夈€?*/
    MINIMAL,

    /** 鏍囧噯锛氬姛鑳藉畬鏁达紝閬靛惊鍔╂墜璁剧疆涓殑宸ュ叿锛岄粯璁や笉娉ㄥ叆 use_skill锛屽彲鍦ㄨ缃腑鎸夐渶寮€鍚紱涓嶆敞鍏ュ伐浣滃尯/淇′换鏂囦欢澶瑰伐鍏蜂笌 AGENTS 璇存槑锛屼笉鏀寔 skill/MCP 鎰熺煡涓庨厤缃€?*/
    STANDARD,

    /** PTC锛圲I 鏄剧ず銆屽伐浣滃尯妯″紡銆嶏級锛氬寘鍚爣鍑嗗叏閮ㄨ兘鍔涳紝骞跺惎鐢ㄤ俊浠绘枃浠跺す涓庡伐浣滃尯鐨勬墍鏈夊伐鍏疯兘鍔涳紙鏈厤缃椂鑷姩闄嶇骇锛夈€?*/
    PTC,

    /** CREATIVE锛圲I 鏄剧ず銆岀鐞嗘ā寮忋€嶏級锛氬寘鍚伐浣滃尯鍏ㄩ儴鑳藉姏锛屽苟鏀寔 skill/MCP/鎻愪緵鍟?鍔╂墜/鍏ㄥ眬璁剧疆/鎼滅储鏈嶅姟绠＄悊銆佺幆澧冧笌鏃ュ織璇诲彇銆佹柊妯″紡鍐欏叆锛堝啓鎿嶄綔闇€瀹℃壒锛夈€?*/
    CREATIVE;

    fun policy(): ChatModePolicy = when (this) {
        MINIMAL -> ChatModePolicy.MINIMAL
        STANDARD -> ChatModePolicy.STANDARD
        PTC -> ChatModePolicy(
            capabilities = ChatModePolicy.STANDARD.capabilities +
                Capability.SKILL_USE + Capability.WORKSPACE + Capability.TRUSTED_FOLDER
        )
        CREATIVE -> ChatModePolicy(
            capabilities = ChatModePolicy.STANDARD.capabilities +
                Capability.SKILL_USE +
                Capability.WORKSPACE + Capability.TRUSTED_FOLDER +
                Capability.SKILL_ADMIN + Capability.MCP_ADMIN + Capability.CREATIVE_TOOLS +
                Capability.DEVICE_TOOLS +
                Capability.PROVIDER_ADMIN + Capability.ASSISTANT_ADMIN +
                Capability.SETTINGS_ADMIN + Capability.DATA_ADMIN
        )
    }
}

/** 鍐呯疆妯″紡鐢熸晥绛栫暐锛氱敤鎴疯鐩栦紭鍏堬紝鍚﹀垯浣跨敤鍑哄巶榛樿銆?*/
fun ChatMode.effectivePolicy(settings: Settings): ChatModePolicy =
    settings.builtinModeOverrides[this] ?: policy()

/** 妯″紡鑳藉姏涓洜褰撳墠鍔╂墜鎴栧叏灞€璁剧疆闄愬埗鑰屽疄闄呬笉鍙敤鐨勯」銆?*/
fun ChatModePolicy.restrictedCapabilities(settings: Settings): Set<Capability> = buildSet {
    val assistant = settings.getCurrentAssistant()
    val builtInSearchEnabled = settings.getCurrentChatModel()?.tools?.contains(BuiltInTools.Search) == true
    if (Capability.SEARCH in capabilities && !assistant.enableWebSearch && !builtInSearchEnabled) {
        add(Capability.SEARCH)
    }
    if (Capability.WORKSPACE in capabilities && assistant.workspaceId == null) add(Capability.WORKSPACE)
    if (Capability.MCP_USE in capabilities &&
        (assistant.mcpServers.isEmpty() || !settings.enableMcpManager)
    ) {
        add(Capability.MCP_USE)
    }
    if (Capability.MCP_ADMIN in capabilities && !settings.enableMcpManager) add(Capability.MCP_ADMIN)
    if (Capability.SKILL_USE in capabilities && assistant.enabledSkills.isEmpty()) add(Capability.SKILL_USE)
    if (Capability.MEMORY in capabilities && !assistant.enableMemory) add(Capability.MEMORY)
    if (Capability.TODO in capabilities && !settings.enableTodoList) add(Capability.TODO)
    if (Capability.SUBAGENT in capabilities && !settings.enableSubAgent) add(Capability.SUBAGENT)
    if (Capability.STUDY in capabilities && assistant.enabledStudyTools.isEmpty()) add(Capability.STUDY)
    if (Capability.HISTORY in capabilities && !assistant.enableRecentChatsReference) {
        add(Capability.HISTORY)
    }
    if (Capability.KNOWLEDGE in capabilities && assistant.knowledgeBaseIds.isEmpty()) {
        add(Capability.KNOWLEDGE)
    }
}

/**
 * 鑳藉姏椤癸細涓€涓伐鍏锋棌鎴栨彁绀鸿瘝鐗囨鐨勯棬鎺у崟鍏冦€傛ā寮忓嵆涓€浠借兘鍔涙竻鍗曪紙[ChatModePolicy.capabilities]锛夛紝
 * 娓呭崟閲屽垪鍑鸿妯″紡鍏佽娉ㄥ叆鐨勮兘鍔涳紝涓?dsh 鐨?agent preset锛坅gent.cordis.yml 缁勮琛岋級鍚屾€濊矾锛?
 * preset 鍐冲畾鍙鎬э紝娉ㄥ唽琛?娌欑/瀹℃壒/鎸佷箙鍖栫瓑瀹夸富灞備笉鍔ㄣ€?
 */
@Serializable
enum class Capability(val managementOnly: Boolean = false) {
    /** 鏈湴宸ュ叿鏃忥紙鏃堕棿/鍓创鏉?JS 绛夛級 */
    LOCAL_TOOLS,

    /** 鑱旂綉鎼滅储宸ュ叿鏃?*/
    SEARCH,

    /** 闄勪欢鏂囨。瑙ｆ瀽涓?OCR 娉ㄥ叆 */
    DOCUMENT,

    /** workspace 宸ュ叿鏃?+ AGENTS.md/宸ヤ綔鍖虹幆澧冭鏄庢敞鍏?*/
    WORKSPACE,

    /** 淇′换鏂囦欢澶瑰伐鍏锋棌 + 鐜璇存槑娉ㄥ叆 */
    TRUSTED_FOLDER,

    /** use_skill锛堝凡鍚敤 skill 鐨勪娇鐢級 */
    SKILL_USE,

    /** skill_admin_*锛堟劅鐭ヤ笌閰嶇疆 skill锛?*/
    SKILL_ADMIN,

    /** 澶栭儴 MCP 宸ュ叿 mcp__* */
    MCP_USE,

    /** mcp_admin_*锛堟劅鐭ヤ笌閰嶇疆 MCP锛?*/
    MCP_ADMIN,

    /** 璁板繂宸ュ叿涓庤蹇嗘彁绀鸿瘝 */
    MEMORY,

    /** todo 宸ュ叿 */
    TODO,

    /** 瀛愪唬鐞嗗伐鍏?*/
    SUBAGENT,

    /** 瀛︿範宸ュ叿锛堢敓璇?绗旇/閿欓/鐭ヨ瘑鍗?娴嬮獙锛?*/
    STUDY,

    /** 设备工具族（诊断/存储/冻结，依赖 Shizuku） */
    DEVICE_TOOLS,

    /** 鍘嗗彶瀵硅瘽寮曠敤/浼氳瘽鎼滅储 */
    HISTORY,

    /** 鐭ヨ瘑搴撴绱?*/
    KNOWLEDGE,

    /** 妯″紡娉ㄥ叆/lorebook 鎻愮ず璇嶆敞鍏?*/
    PROMPT_INJECTION,

    /** 鏃堕棿鎻愰啋/todo 鎻愰啋 */
    REMINDERS,

    /** tool.systemPrompt 寰幆 */
    TOOL_SYSTEM_PROMPT,

    /** agent behavior 琛屼负灞傛彁绀鸿瘝锛堢嫭绔嬩簬 tool.systemPrompt锛?*/
    AGENT_BEHAVIOR_PROMPT,

    /** env_inspect/app_logs/provider_add/mode_create/mode_update/mode_delete */
    CREATIVE_TOOLS(managementOnly = true),

    /** provider_list/provider_get/provider_update/provider_delete/provider_test */
    PROVIDER_ADMIN(managementOnly = true),

    /** assistant_list/assistant_get/assistant_create/assistant_update/assistant_duplicate/assistant_delete */
    ASSISTANT_ADMIN(managementOnly = true),

    /** settings_admin_list/settings_admin_get/settings_admin_set */
    SETTINGS_ADMIN(managementOnly = true),

    /** search_admin_* 涓?admin_inventory */
    DATA_ADMIN(managementOnly = true),
}

/** 琛屼负椋庢牸锛氱敱鑳藉姏娓呭崟娲剧敓鐨勬墽琛屽噯鍒欙紝鍐冲畾 agent behavior 鎻愮ず璇嶆敞鍏ュ摢涓€娈垫ā寮忔寚瀵笺€?*/
@Serializable
enum class AgentBehaviorProfile {
    /** 閫氱敤锛氬伐鍏烽綈鍏紝浣嗘寜闇€浣跨敤锛屼笉涓诲姩鎵╁ぇ浠诲姟鑼冨洿銆?*/
    STANDARD,

    /** 宸ヤ綔鍖猴細浠ラ」鐩枃浠朵负涓績锛岃繛缁帹杩涘姝ヤ换鍔″苟楠岃瘉缁撴灉銆?*/
    WORKSPACE,

    /** 绠＄悊锛氫互鍙鎰熺煡涓哄墠鎻愶紝鍐欐搷浣滆鏄庡奖鍝嶅苟绛夊緟瀹℃壒銆?*/
    MANAGEMENT,

    /** 鏋佺畝锛氶粯璁や笉璋冪敤宸ュ叿锛屽彧鍦ㄧ敤鎴锋槑纭姹傛椂浣跨敤銆?*/
    MINIMAL,

    /** 鏃犳ā寮忕増鏈涓猴細涓嶆敞鍏ユā寮忓紩瀵硷紝淇濈暀鍐崇瓥銆佸伐鍏峰垎缁勪笌瀛愪唬鐞嗚鏄庛€?*/
    LEGACY,
}

/**
 * 妯″紡绛栫暐锛氱敓鎴愭椂鐨勫伐鍏锋棌/鎻愮ず璇嶇墖娈?鐜璇存槑闂ㄦ帶娓呭崟銆?
 * 搴忓垪鍖栧瓨 [capabilities]锛屽竷灏斿瓧娈典负娲剧敓瑙嗗浘锛屾敞鍏ラ摼璺鍙栦笉鍙楀奖鍝嶃€?
 */
@Serializable
data class ChatModePolicy(
    /** 璇ユā寮忓厑璁告敞鍏ョ殑鑳藉姏娓呭崟 */
    val capabilities: Set<Capability> = DEFAULT_CAPABILITIES,
    /** 鏄惧紡琛屼负椋庢牸锛沶ull = 鎸夎兘鍔涙竻鍗曡嚜鍔ㄦ帹瀵?*/
    val behaviorProfileOverride: AgentBehaviorProfile? = null,
) {
    val allowWorkspace: Boolean get() = Capability.WORKSPACE in capabilities
    val allowTrustedFolder: Boolean get() = Capability.TRUSTED_FOLDER in capabilities
    val allowSkillUse: Boolean get() = Capability.SKILL_USE in capabilities
    val allowSkillAdmin: Boolean get() = Capability.SKILL_ADMIN in capabilities
    val allowMcpUse: Boolean get() = Capability.MCP_USE in capabilities
    val allowMcpAdmin: Boolean get() = Capability.MCP_ADMIN in capabilities
    val allowMemory: Boolean get() = Capability.MEMORY in capabilities
    val allowTodo: Boolean get() = Capability.TODO in capabilities
    val allowSubAgent: Boolean get() = Capability.SUBAGENT in capabilities
    val allowStudy: Boolean get() = Capability.STUDY in capabilities
    val allowDeviceTools: Boolean get() = Capability.DEVICE_TOOLS in capabilities
    val allowHistory: Boolean get() = Capability.HISTORY in capabilities
    val allowKnowledge: Boolean get() = Capability.KNOWLEDGE in capabilities
    val includePromptInjection: Boolean get() = Capability.PROMPT_INJECTION in capabilities
    val includeReminders: Boolean get() = Capability.REMINDERS in capabilities
    val includeToolSystemPrompt: Boolean get() = Capability.TOOL_SYSTEM_PROMPT in capabilities
    val allowCreativeTools: Boolean get() = Capability.CREATIVE_TOOLS in capabilities
    val allowProviderAdmin: Boolean get() = Capability.PROVIDER_ADMIN in capabilities
    val allowAssistantAdmin: Boolean get() = Capability.ASSISTANT_ADMIN in capabilities
    val allowSettingsAdmin: Boolean get() = Capability.SETTINGS_ADMIN in capabilities
    val allowDataAdmin: Boolean get() = Capability.DATA_ADMIN in capabilities
    val allowLocalTools: Boolean get() = Capability.LOCAL_TOOLS in capabilities
    val allowSearch: Boolean get() = Capability.SEARCH in capabilities
    val allowDocument: Boolean get() = Capability.DOCUMENT in capabilities
    val behaviorProfile: AgentBehaviorProfile
        get() = behaviorProfileOverride ?: when {
            allowCreativeTools || allowProviderAdmin || allowAssistantAdmin ||
                allowSettingsAdmin || allowDataAdmin || allowSkillAdmin || allowMcpAdmin ->
                AgentBehaviorProfile.MANAGEMENT
            allowWorkspace || allowTrustedFolder -> AgentBehaviorProfile.WORKSPACE
            capabilities == MINIMAL_CAPABILITIES -> AgentBehaviorProfile.MINIMAL
            else -> AgentBehaviorProfile.STANDARD
        }

    /** 鏄惁娉ㄥ叆 agent behavior 鎻愮ず璇嶏細鐙珛鑳藉姏寮€鍏筹紝鏋佺畝妯″紡榛樿淇濈暀涓€娈佃涓哄噯鍒欍€?*/
    val includeAgentBehaviorPrompt: Boolean
        get() = Capability.AGENT_BEHAVIOR_PROMPT in capabilities ||
            behaviorProfile == AgentBehaviorProfile.MINIMAL

    companion object {
        /** 榛樿鑳藉姏锛堟爣鍑嗘ā寮忓熀纭€锛夛細鏈湴宸ュ叿/鎼滅储/闄勪欢瑙ｆ瀽/MCP/璁板繂/鎵╁睍宸ュ叿/鎻愮ず璇嶆敞鍏?鎻愰啋/宸ュ叿鎻愮ず璇?琛屼负灞傛彁绀鸿瘝 */
        val DEFAULT_CAPABILITIES: Set<Capability> = setOf(
            Capability.LOCAL_TOOLS,
            Capability.SEARCH,
            Capability.DOCUMENT,
            Capability.MCP_USE,
            Capability.MEMORY,
            Capability.TODO,
            Capability.SUBAGENT,
            Capability.STUDY,
            Capability.HISTORY,
            Capability.KNOWLEDGE,
            Capability.PROMPT_INJECTION,
            Capability.REMINDERS,
            Capability.TOOL_SYSTEM_PROMPT,
            Capability.AGENT_BEHAVIOR_PROMPT,
        )

        /** 鏍囧噯妯″紡绛栫暐锛氶粯璁よ兘鍔涳紝涓嶆敞鍏?use_skill */
        val STANDARD = ChatModePolicy(capabilities = DEFAULT_CAPABILITIES)

        /** 鏋佺畝妯″紡鑳藉姏娓呭崟锛氭湰鍦板伐鍏?鎼滅储/闄勪欢瑙ｆ瀽 */
        val MINIMAL_CAPABILITIES: Set<Capability> =
            setOf(Capability.LOCAL_TOOLS, Capability.SEARCH, Capability.DOCUMENT)

        /** 鏋佺畝妯″紡绛栫暐锛氫笉娉ㄥ叆宸ュ叿澹版槑锛屼絾浠嶄繚鐣欎竴娈点€岄粯璁や笉涓诲姩璋冪敤宸ュ叿銆嶇殑琛屼负鍑嗗垯 */
        val MINIMAL = ChatModePolicy(
            capabilities = MINIMAL_CAPABILITIES,
            behaviorProfileOverride = AgentBehaviorProfile.MINIMAL,
        )

        /** 璺熼殢鍔╂墜閰嶇疆鑳藉姏闆嗗悎锛氱瓑浠蜂簬寮曞叆鍥涗釜妯″紡鍓嶇殑瀹屾暣宸ュ叿/鎻愮ず璇嶈兘鍔涳紝浠呮帓闄ょ鐞嗘ā寮忎笓灞炲伐鍏枫€?*/
        val UNRESTRICTED_CAPABILITIES: Set<Capability> =
            Capability.entries.filterNot { it.managementOnly }.toSet()

        /** 璺熼殢鍔╂墜閰嶇疆绛栫暐锛氭棤妯″紡闂ㄦ帶锛岃涓烘彁绀鸿瘝杩樺師鏃犳ā寮忕増鏈€?*/
        val UNRESTRICTED = ChatModePolicy(
            capabilities = UNRESTRICTED_CAPABILITIES,
            behaviorProfileOverride = AgentBehaviorProfile.LEGACY,
        )
    }
}

/** 绠＄悊妯″紡鐢熸垚鐨勮嚜瀹氫箟妯″紡閰嶇疆锛屽啓鍏?[Settings.customModes]銆?*/
@Serializable
data class CustomModeConfig(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val description: String = "",
    val policy: ChatModePolicy = ChatModePolicy(),
)

/** 浼氳瘽鍐呮ā寮忓紩鐢ㄧ殑搴忓垪鍖栵細鍐呯疆妯″紡瀛樻灇涓惧悕锛岃嚜瀹氫箟妯″紡瀛?`custom:<id>`銆?*/
object ModeRefs {
    const val CUSTOM_PREFIX = "custom:"

    /** 銆岃窡闅忓姪鎵嬮厤缃€嶄吉鏉＄洰寮曠敤锛屼粎鐢ㄤ簬 mode_list 灞曠ず涓庨槻寰℃€цВ鏋愶紝涓嶈惤搴撲负浼氳瘽 mode銆?*/
    const val FOLLOW_ASSISTANT = "follow_assistant"

    fun builtin(mode: ChatMode): String = mode.name

    fun custom(id: String): String = "$CUSTOM_PREFIX$id"

    fun parseBuiltin(value: String?): ChatMode? =
        value?.let { runCatching { ChatMode.valueOf(it) }.getOrNull() }
}

/**
 * 榛樿妯″紡瑙ｆ瀽锛堝崟涓€鏁版嵁婧愶級锛氬姪鎵嬫樉寮忛厤缃?> 鍏ㄥ眬鏄惧紡閰嶇疆銆?
 *
 * 鏈樉寮忛厤缃椂杩斿洖 null锛岃〃绀轰細璇濅娇鐢ㄣ€岃窡闅忓姪鎵嬮厤缃€嶃€?
 */
@Suppress("UNUSED_PARAMETER")
fun resolveModeRef(assistant: Assistant, settings: Settings, trustedFolderActive: Boolean): String? =
    assistant.defaultMode
        ?: settings.defaultMode

/** 鎶婃ā寮忓紩鐢ㄨВ鏋愪负绛栫暐锛涘紩鐢ㄤ负绌烘垨鎸囧悜涓嶅瓨鍦ㄧ殑妯″紡鏃惰繑鍥?null銆?*/
fun resolveModePolicy(ref: String?, settings: Settings): ChatModePolicy? {
    if (ref.isNullOrBlank()) return null
    if (ref.startsWith(ModeRefs.CUSTOM_PREFIX)) {
        val custom = settings.customModes.find { it.id == ref.removePrefix(ModeRefs.CUSTOM_PREFIX) }
        return custom?.policy
    }
    return ModeRefs.parseBuiltin(ref)?.effectivePolicy(settings)
}

/**
 * 浼氳瘽绾х敓鏁堢瓥鐣ワ細mode 涓?null 鏃朵娇鐢ㄣ€岃窡闅忓姪鎵嬮厤缃€嶏紱鏄惧紡妯″紡鎸夊紩鐢ㄨВ鏋愶紝
 * 闈炴硶鎴栧凡鍒犻櫎鐨勬樉寮忓紩鐢ㄥ洖閫€鏍囧噯妯″紡銆?
 */
fun resolveConversationPolicy(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    trustedFolderActive: Boolean,
): ChatModePolicy {
    val modeStr = conversation.mode
    if (modeStr.isNullOrBlank() || modeStr == ModeRefs.FOLLOW_ASSISTANT) {
        return ChatModePolicy.UNRESTRICTED
    }
    return resolveModePolicy(ref = modeStr, settings = settings) ?: ChatMode.STANDARD.effectivePolicy(settings)
}
