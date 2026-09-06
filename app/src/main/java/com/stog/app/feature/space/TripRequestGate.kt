package com.stog.app.feature.space

internal class TripRequestGate {
    private var selectionVersion = 0L

    fun capture(): Long = selectionVersion

    fun begin(): Long {
        invalidate()
        return capture()
    }

    fun invalidate() {
        selectionVersion += 1
    }

    fun applyIfCurrent(requestVersion: Long, action: () -> Unit) {
        if (requestVersion == selectionVersion) {
            action()
        }
    }
}
