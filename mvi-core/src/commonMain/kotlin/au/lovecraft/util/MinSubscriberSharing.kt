package au.lovecraft.util

import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Makes a [SharedFlow] or [StateFlow] wait until it has at least [minSubscribers] before
 * starting to emit values.
 */
fun SharingStarted.Companion.WhileSubscribedAtLeast(
    minSubscribers: Int
) = SharingStarted { subscriptionCount: StateFlow<Int> ->
    subscriptionCount.map { count ->
        if (count >= minSubscribers) SharingCommand.START else SharingCommand.STOP
    }.distinctUntilChanged()
}