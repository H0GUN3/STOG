package com.stog.app.feature.space

import com.stog.app.feature.plan.trip.TripCardData
import com.stog.app.feature.plan.trip.tripCardData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal suspend fun loadTripCards(
    planning: PlanningApiClient,
    accessToken: String,
    trips: List<TripSummary>,
): List<TripCardData> = coroutineScope {
    trips.map { trip ->
        async(Dispatchers.IO) {
            val (members, placeCount, imageUrl) = coroutineScope {
                val membersRequest = async {
                    runCatching {
                        planning.members(accessToken, trip.id).members
                            .joinToString(" · ") { it.nickname }
                            .takeIf(String::isNotBlank)
                    }.getOrNull()
                }
                val placeCountRequest = async {
                    runCatching {
                        planning.basket(accessToken, trip.id).size
                    }.getOrDefault(0)
                }
                val imageRequest = async {
                    trip.coverImageUrl ?: runCatching {
                        planning.tripPhotos(accessToken, trip.id).firstOrNull()?.thumbnailUrl
                    }.getOrNull()
                }
                Triple(
                    membersRequest.await(),
                    placeCountRequest.await(),
                    imageRequest.await(),
                )
            }
            tripCardData(
                trip = trip,
                imageRes = tripImageResource(trip.title),
                imageUrl = imageUrl,
                memberLabel = members,
                placeCount = placeCount,
            )
        }
    }.awaitAll()
}
