package me.rerere.rikkahub.data.device

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 设备操作安全守卫：冻结/清理前检查目标是否受保护。
 *
 * 三层名单：
 * 1. 系统硬保护（动态检测 + 固定）：桌面、输入法、本应用、系统关键进程，任何情况下不可冻结
 * 2. 默认保护：微信、QQ、支付宝等，默认不可冻结，用户可在设置中移除
 * 3. 用户自定义保护：用户在设置中手动添加
 *
 * [checkFreeze] 返回 null 表示允许冻结，否则返回拒绝原因。
 */
class SafetyGuard(
    private val context: Context,
    private val store: DeviceSafetyStore,
) {
    /** 固定系统硬保护包名 */
    private val hardProtectedPackages = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.server.telecom",
    )

    /** 默认保护包名：微信、QQ、支付宝 */
    private val defaultProtectedPackages = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.eg.android.AlipayGphone",
    )

    /** 动态检测桌面 launcher 包名 */
    private fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = runCatching {
            context.packageManager.resolveActivity(intent, 0)
        }.getOrNull()
        return buildSet {
            resolve?.activityInfo?.packageName?.let { add(it) }
        }
    }

    /** 动态检测已启用输入法包名（Settings.Secure.ENABLED_INPUT_METHODS） */
    private fun imePackages(): Set<String> {
        val raw = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
        }.getOrNull() ?: return emptySet()
        return raw.split(':').mapNotNull { component ->
            component.substringBefore('/').takeIf { it.isNotBlank() }
        }.toSet()
    }

    /** 系统硬保护：动态 + 固定 + 本应用 */
    private fun isHardProtected(packageName: String): Boolean {
        if (packageName in hardProtectedPackages) return true
        if (packageName == context.packageName) return true
        if (packageName in launcherPackages()) return true
        if (packageName in imePackages()) return true
        return false
    }

    /**
     * 校验目标包是否可冻结。
     * @return null 表示允许；否则为拒绝原因
     */
    suspend fun checkFreeze(packageName: String): String? {
        if (packageName.isBlank()) return "包名为空"
        if (isHardProtected(packageName)) {
            return "系统关键应用不可冻结"
        }
        val userUnprotected = store.userUnprotectedSnapshot()
        if (packageName in defaultProtectedPackages && packageName !in userUnprotected) {
            return "该应用在默认保护名单中（微信/QQ/支付宝等），如需冻结请先在设置中移除保护"
        }
        if (packageName in store.userProtectedSnapshot()) {
            return "该应用在用户保护名单中"
        }
        return null
    }

    /** 系统硬保护名单（用于设置页展示） */
    suspend fun hardProtectedSnapshot(): Set<String> =
        hardProtectedPackages + launcherPackages() + imePackages() + context.packageName

    /** 默认保护名单 */
    fun defaultProtectedSnapshot(): Set<String> = defaultProtectedPackages
}