package au.lovecraft.mvi

import kotlin.jvm.JvmInline

/**
 * Represents a value that may not yet be available when using synchronous MVI reducers.
 *
 * This is a useful type for MVI state parameters that load asynchronously.
 *
 * Using [Async] instead of `null` makes intent explicit and avoids ambiguous "not loaded" states.
 * - [Determining]: the value is being resolved.
 * - [Determined]: the value is available.
 */
sealed interface Async<out T> {
    data object Determining : Async<Nothing>

    @JvmInline
    value class Determined<T>(val value: T) : Async<T>
}

/**
 * Convenience for Async<Boolean>: returns true only when this is [Async.Determined] with value true.
 */
fun Async<Boolean>.isTrue() = (this as? Async.Determined<Boolean>)?.value ?: false

fun <T> T.asDetermined() = Async.Determined(this)