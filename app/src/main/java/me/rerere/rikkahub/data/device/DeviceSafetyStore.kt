package me.rerere.rikkahub.data.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.safetyDataStore by preferencesDataStore("device_safety")

/**
 * 设备安全名单存储：
 * - [userProtected]：用户额外保护的应用（不可冻结）
 * - [userUnprotected]：用户主动从默认保护名单中移除的应用
 */
class DeviceSafetyStore(private val context: Context) {
    private val protectedKey = stringSetPreferencesKey("user_protected")
    private val unprotectedKey = stringSetPreferencesKey("user_unprotected")

    val userProtected: Flow<Set<String>> =
        context.safetyDataStore.data.map { it[protectedKey] ?: emptySet() }

    val userUnprotected: Flow<Set<String>> =
        context.safetyDataStore.data.map { it[unprotectedKey] ?: emptySet() }

    suspend fun userProtectedSnapshot(): Set<String> = userProtected.first()

    suspend fun userUnprotectedSnapshot(): Set<String> = userUnprotected.first()

    suspend fun addProtected(packageName: String) {
        context.safetyDataStore.edit { prefs ->
            prefs[protectedKey] = (prefs[protectedKey] ?: emptySet()) + packageName
        }
    }

    suspend fun removeProtected(packageName: String) {
        context.safetyDataStore.edit { prefs ->
            prefs[protectedKey] = (prefs[protectedKey] ?: emptySet()) - packageName
        }
    }

    suspend fun addUnprotected(packageName: String) {
        context.safetyDataStore.edit { prefs ->
            prefs[unprotectedKey] = (prefs[unprotectedKey] ?: emptySet()) + packageName
        }
    }

    suspend fun removeUnprotected(packageName: String) {
        context.safetyDataStore.edit { prefs ->
            prefs[unprotectedKey] = (prefs[unprotectedKey] ?: emptySet()) - packageName
        }
    }
}