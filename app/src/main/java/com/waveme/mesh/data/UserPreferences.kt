package com.waveme.mesh.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("wave_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_AVATAR_URI = "avatar_uri"
        private const val KEY_STATUS = "status"
        private const val KEY_BIO = "bio"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_USER_GROUPS = "user_groups"
        private const val KEY_ECO_MODE = "eco_mode"
        private const val KEY_BLOCKED_USERS = "blocked_users"
        val DEFAULT_CHANNELS = listOf("Music", "Sports", "Tech")
    }

    fun getUserId(): String {
        var id = prefs.getString(KEY_USER_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_USER_ID, id).apply()
        }
        return id!!
    }

    fun getUsername(): String {
        return prefs.getString(KEY_USERNAME, "User-${getUserId().take(4)}") ?: "Unknown"
    }

    fun setUsername(name: String) {
        prefs.edit().putString(KEY_USERNAME, name).apply()
    }

    fun getAvatarUri(): String? {
        return prefs.getString(KEY_AVATAR_URI, null)
    }

    fun setAvatarUri(uri: String) {
        prefs.edit().putString(KEY_AVATAR_URI, uri).apply()
    }

    fun getStatus(): String {
        return prefs.getString(KEY_STATUS, "") ?: ""
    }

    fun setStatus(status: String) {
        prefs.edit().putString(KEY_STATUS, status).apply()
    }

    fun getBio(): String {
        return prefs.getString(KEY_BIO, "") ?: ""
    }

    fun setBio(bio: String) {
        prefs.edit().putString(KEY_BIO, bio).apply()
    }

    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    fun isEcoMode(): Boolean {
        return prefs.getBoolean(KEY_ECO_MODE, false)
    }

    fun setEcoMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ECO_MODE, enabled).apply()
    }

    fun blockUser(deviceId: String) {
        val blocked = getBlockedUsers().toMutableSet()
        blocked.add(deviceId)
        prefs.edit().putStringSet(KEY_BLOCKED_USERS, blocked).apply()
    }

    fun unblockUser(deviceId: String) {
        val blocked = getBlockedUsers().toMutableSet()
        blocked.remove(deviceId)
        prefs.edit().putStringSet(KEY_BLOCKED_USERS, blocked).apply()
    }

    fun getBlockedUsers(): Set<String> {
        return prefs.getStringSet(KEY_BLOCKED_USERS, emptySet()) ?: emptySet()
    }

    fun getUserGroups(): Set<String> {
        return getUserGroupsWithExpiry().keys
    }

    fun getUserGroupsWithExpiry(): Map<String, Long> {
        val groups = prefs.getStringSet(KEY_USER_GROUPS, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, Long>()
        
        DEFAULT_CHANNELS.forEach { result[it] = Long.MAX_VALUE }
        
        val stillValid = mutableSetOf<String>()

        groups.forEach { entry ->
            val name = entry.substringBefore("_EXP_")
            val expiryStr = entry.substringAfterLast("_EXP_", "")
            
            if (name in DEFAULT_CHANNELS) {
                stillValid.add(name)
            } else if (expiryStr.isEmpty()) {
                result[name] = Long.MAX_VALUE
                stillValid.add(entry)
            } else {
                val expiry = expiryStr.toLongOrNull() ?: 0L
                if (expiry > now) {
                    result[name] = expiry
                    stillValid.add(entry)
                }
            }
        }

        if (stillValid.size != groups.size) {
            prefs.edit().putStringSet(KEY_USER_GROUPS, stillValid).apply()
        }
        
        return result
    }

    fun saveUserGroups(groups: Set<String>) {
        val existingWithExpiry = getUserGroupsWithExpiry()
        val newExpiryTime = System.currentTimeMillis() + (19 * 60 * 1000)
        
        val encoded = groups.map { name ->
            when {
                name in DEFAULT_CHANNELS -> name
                existingWithExpiry.containsKey(name) -> {
                    val expiry = existingWithExpiry[name]
                    if (expiry == Long.MAX_VALUE) name else "${name}_EXP_$expiry"
                }
                else -> "${name}_EXP_$newExpiryTime"
            }
        }.toSet()

        prefs.edit().putStringSet(KEY_USER_GROUPS, encoded).apply()
    }

    fun clearAllData() {
        prefs.edit().clear().apply()
    }
}
