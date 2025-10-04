package au.lovecraft.mvi

import co.touchlab.kermit.Logger
import kotlinx.coroutines.channels.BufferOverflow.DROP_LATEST
import kotlinx.coroutines.channels.BufferOverflow.SUSPEND
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlin.reflect.KClass

/**
 * Creates a Channel for programmatic (async) intents with strict delivery guarantees.
 *
 * Characteristics:
 * - Capacity: RENDEZVOUS — the producer suspends until the consumer begins handling the element.
 * - Overflow: SUSPEND — additional producers also suspend; no elements are dropped.
 *
 * This configuration helps maintain a predictable order of internal processing and
 * treats dropped async intents as an error condition.
 *
 * Logging:
 * If an async intent is ever undelivered, a non-fatal error is logged.
 */
inline fun <reified ViewModel : MviViewModel<*, *>, AsyncIntent : Any> ViewModel.mviAsyncIntentChannel() =
    Channel<AsyncIntent>(
        capacity = RENDEZVOUS,
        onBufferOverflow = SUSPEND,
        onUndeliveredElement = { asyncIntent ->
            Logger.e(formatDropMessage(ViewModel::class, "async", asyncIntent))
        },
    )

/**
 * Creates a Channel for user-driven intents optimized for UI throughput.
 *
 * Characteristics:
 * - Capacity: 1 — allows one pending user intent.
 * - Overflow: DROP_LATEST — when a pending intent exists, new ones are dropped to avoid backpressure.
 *
 * This trades perfect delivery for responsiveness under load; users can repeat actions if needed.
 *
 * Logging:
 * Dropped user intents are logged as warnings to aid debugging and performance analysis.
 */
inline fun <reified ViewModel : MviViewModel<*, *>, UserIntent : Any> ViewModel.mviUserIntentChannel() =
    Channel<UserIntent>(
        capacity = 1,
        onBufferOverflow = DROP_LATEST, // Ignore users intent if handling of a previous hasn't started
        onUndeliveredElement = { userIntent ->
            Logger.e(formatDropMessage(ViewModel::class, "user", userIntent))
        },
    )

fun <Intent : Any> formatDropMessage(source: KClass<*>, typeName: String, intent: Intent): String =
    "${source.simpleName} dropped $typeName intent: ${intent::class.simpleName}"
