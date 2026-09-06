package com.stog.app.feature.space

import android.net.Uri

internal const val STOG_INVITE_SCHEME = "stog"
internal const val STOG_INVITE_HOST = "trip-invites"
internal const val STOG_INVITE_WEB_BASE_URL =
    "https://stog-backend-qoeu5cmuxq-as.a.run.app"
private const val STOG_INVITE_WEB_HOST = "stog-backend-qoeu5cmuxq-as.a.run.app"

internal enum class TripInviteEntry {
    LOGIN,
    JOIN,
}

internal fun TripInvite.shareLink(): String = tripInviteShareLink(token)

internal fun TripInvite.shareMessage(tripTitle: String): String =
    "$tripTitle 여행에 초대합니다.\n${shareLink()}"

internal fun tripInviteShareLink(token: String): String {
    require(token.isValidTripInviteToken()) { "Invalid trip invite token" }
    return "$STOG_INVITE_WEB_BASE_URL/$STOG_INVITE_HOST/$token"
}

internal fun tripInviteTokenFromUri(uri: Uri?): String? {
    val token = when {
        uri?.scheme == STOG_INVITE_SCHEME && uri.host == STOG_INVITE_HOST ->
            uri.pathSegments.singleOrNull()
        uri?.scheme == "https" && uri.host == STOG_INVITE_WEB_HOST &&
            uri.pathSegments.firstOrNull() == STOG_INVITE_HOST ->
            uri.pathSegments.drop(1).singleOrNull()
        else -> null
    }
    return token?.takeIf(String::isValidTripInviteToken)
}

internal fun tripInviteEntryFor(accessToken: String?): TripInviteEntry =
    if (accessToken.isNullOrBlank()) TripInviteEntry.LOGIN else TripInviteEntry.JOIN

private fun String.isValidTripInviteToken(): Boolean =
    isNotBlank() && all { character ->
        character in 'a'..'z' ||
            character in 'A'..'Z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'
    }
