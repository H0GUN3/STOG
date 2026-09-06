package com.stog.app.feature.record

import com.stog.app.feature.auth.AuthSessionRuntime
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class PhotoHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal interface PhotoHttpTransport {
    fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
    ): PhotoHttpResponse
}

internal class UrlConnectionPhotoTransport : PhotoHttpTransport {
    override fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): PhotoHttpResponse {
        val bearer = headers["Authorization"]
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.takeIf(String::isNotBlank)
        val retrier = AuthSessionRuntime.retrier
        return if (retrier == null || bearer == null) {
            requestWithHeaders(method, url, headers, body)
        } else {
            retrier.execute(
                accessToken = bearer,
                isAuthenticationFailure = {
                    it is PhotoRequestException && it.statusCode == 401
                },
            ) { token ->
                requestWithHeaders(
                    method,
                    url,
                    headers + ("Authorization" to "Bearer $token"),
                    body,
                )
            }
        }
    }

    private fun requestWithHeaders(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): PhotoHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = PHOTO_NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = PHOTO_NETWORK_TIMEOUT_MILLIS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.setFixedLengthStreamingMode(body.size)
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return PhotoHttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }
}

internal class PhotoRequestException(
    val statusCode: Int,
    val code: String,
    val responseBody: String = "",
) : IOException("$code ($statusCode)") {
    val isAuthenticationFailure: Boolean
        get() = statusCode == 401
}

internal class PhotoResponseDecodingException(cause: Throwable) :
    RuntimeException("Malformed photo API response", cause)

internal const val PHOTO_NETWORK_TIMEOUT_MILLIS = 15_000
