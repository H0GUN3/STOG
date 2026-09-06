package com.stog.app.feature.auth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class StogAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: Long,
    val nickname: String,
)

data class StogCurrentUser(
    val id: Long,
    val nickname: String,
)

data class StoredAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val userId: Long,
)

internal class AuthRequestException(
    val statusCode: Int,
    val code: String,
    val responseBody: String = "",
) : IOException("$code ($statusCode)")

internal object AuthSessionRuntime {
    @Volatile
    var retrier: AuthSessionRetrier? = null
}

internal class AuthSessionRetrier(
    private val loadTokens: () -> StoredAuthTokens?,
    private val refresh: (String) -> StogAuthSession,
    private val saveSession: (StogAuthSession) -> Unit,
    private val onSessionRefreshed: (StogAuthSession) -> Unit = {},
    private val onSessionExpired: () -> Unit = {},
) {
    private val refreshLock = Any()
    private var expirationNotified = false

    fun <T> execute(
        accessToken: String,
        isAuthenticationFailure: (Throwable) -> Boolean,
        request: (String) -> T,
    ): T {
        try {
            return request(accessToken)
        } catch (error: Exception) {
            if (!isAuthenticationFailure(error)) throw error
            val retryToken = synchronized(refreshLock) {
                val stored = loadTokens() ?: throw error
                if (stored.accessToken != accessToken) {
                    stored.accessToken
                } else {
                    val session = try {
                        refresh(stored.refreshToken)
                    } catch (refreshError: Exception) {
                        if (refreshError is AuthRequestException &&
                            refreshError.statusCode in 400..499
                        ) {
                            notifySessionExpired()
                        }
                        throw refreshError
                    }
                    saveSession(session)
                    expirationNotified = false
                    onSessionRefreshed(session)
                    session.accessToken
                }
            }
            return try {
                request(retryToken)
            } catch (retryError: Exception) {
                if (isAuthenticationFailure(retryError)) {
                    notifySessionExpired()
                }
                throw retryError
            }
        }
    }

    fun reset() {
        synchronized(refreshLock) {
            expirationNotified = false
        }
    }

    private fun notifySessionExpired() {
        val shouldNotify = synchronized(refreshLock) {
            if (expirationNotified) {
                false
            } else {
                expirationNotified = true
                true
            }
        }
        if (shouldNotify) onSessionExpired()
    }
}

class AuthClient(
    private val baseUrl: String,
) {
    fun exchangeSocialToken(provider: String, token: String): StogAuthSession {
        val connection = URL("${baseUrl.trimEnd('/')}/auth/social")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(
                    JSONObject()
                        .put("provider", provider)
                        .put("token", token)
                        .toString(),
                )
            }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                val errorCode = JSONObject(responseBody)
                    .optJSONObject("error")
                    ?.optString("code")
                    ?.takeIf(String::isNotBlank)
                    ?: "AUTH_LOGIN_FAILED"
                throw IOException(errorCode)
            }

            val response = JSONObject(responseBody)
            return StogAuthSession(
                accessToken = response.getString("access_token"),
                refreshToken = response.getString("refresh_token"),
                userId = response.getJSONObject("user").getLong("id"),
                nickname = response.getJSONObject("user").getString("nickname"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun currentUser(accessToken: String): StogCurrentUser {
        val connection = URL("${baseUrl.trimEnd('/')}/me")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                val code = runCatching {
                    JSONObject(responseBody).optString("code")
                }.getOrNull()?.takeIf(String::isNotBlank) ?: "AUTH_SESSION_INVALID"
                throw AuthRequestException(responseCode, code, responseBody)
            }
            val response = JSONObject(responseBody)
            return StogCurrentUser(
                id = response.getLong("id"),
                nickname = response.getString("nickname"),
            )
        } finally {
            connection.disconnect()
        }
    }

    fun updateNickname(accessToken: String, nickname: String) {
        val connection = URL("${baseUrl.trimEnd('/')}/profile")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().put("nickname", nickname).toString())
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("PROFILE_UPDATE_FAILED")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun refresh(refreshToken: String): StogAuthSession {
        val connection = URL("${baseUrl.trimEnd('/')}/auth/refresh")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().put("refresh_token", refreshToken).toString())
            }
            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) {
                val code = runCatching {
                    JSONObject(responseBody).optString("code")
                }.getOrNull()?.takeIf(String::isNotBlank) ?: "AUTH_REFRESH_FAILED"
                throw AuthRequestException(responseCode, code, responseBody)
            }
            val response = JSONObject(responseBody)
            return StogAuthSession(
                accessToken = response.getString("access_token"),
                refreshToken = response.getString("refresh_token"),
                userId = response.getJSONObject("user").getLong("id"),
                nickname = response.getJSONObject("user").getString("nickname"),
            )
        } finally {
            connection.disconnect()
        }
    }
}

class AuthTokenStore(context: android.content.Context) {
    private val preferences = androidx.security.crypto.EncryptedSharedPreferences.create(
        "stog_auth",
        androidx.security.crypto.MasterKeys.getOrCreate(
            androidx.security.crypto.MasterKeys.AES256_GCM_SPEC,
        ),
        context,
        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(session: StogAuthSession) {
        preferences.edit()
            .putString("access_token", session.accessToken)
            .putString("refresh_token", session.refreshToken)
            .putLong("user_id", session.userId)
            .putString("nickname", session.nickname)
            .apply()
    }

    fun load(): StoredAuthTokens? {
        val accessToken = preferences.getString("access_token", null)
        val refreshToken = preferences.getString("refresh_token", null)
        val userId = preferences.getLong("user_id", 0)
        return if (!accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank() && userId > 0) {
            StoredAuthTokens(accessToken, refreshToken, userId)
        } else {
            null
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}
