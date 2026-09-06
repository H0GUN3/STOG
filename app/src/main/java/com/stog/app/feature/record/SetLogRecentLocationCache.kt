package com.stog.app.feature.record

import java.util.concurrent.ConcurrentHashMap

internal object SetLogRecentLocationCache {
    private data class Key(
        val accountId: String,
        val tripId: String,
        val userId: String,
    )

    private val locations = ConcurrentHashMap<Key, SetLogRecentLocation>()

    fun put(
        accountId: String,
        tripId: String,
        userId: String,
        location: SetLogRecentLocation,
    ) {
        locations[Key(accountId, tripId, userId)] = location
    }

    fun get(
        accountId: String,
        tripId: String,
        userId: String,
    ): SetLogRecentLocation? = locations[Key(accountId, tripId, userId)]

    fun remove(accountId: String, tripId: String, userId: String) {
        locations.remove(Key(accountId, tripId, userId))
    }
}
