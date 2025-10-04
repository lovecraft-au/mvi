@file:OptIn(ExperimentalCoroutinesApi::class)

package au.lovecraft.util

import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

/**
 * Scopes bundles coroutine dispatchers (Main, Logic, I/O) and a [Job], and exposes
 * convenient [CoroutineContext] and [CoroutineScope] accessors for each.
 *
 * Motivation:
 * - Reduce boilerplate for creating scopes across components.
 * - Standardize how dispatchers and jobs are propagated throughout an application.
 * - Encourage explicit dispatcher selection at coroutine launch sites for readability.
 * - Promote structured concurrency via a shared job hierarchy.
 * - Make it easy to refactor code across dispatchers without changing call sites.
 *
 * Child scopes can be created that share the same dispatchers and derive from the parent [Job].
 */
class Scopes(
    val mainDispatcher: CoroutineDispatcher,
    val logicDispatcher: CoroutineDispatcher,
    val ioDispatcher: CoroutineDispatcher,
    val job: Job = Job(),
) {
    // region CoroutineContext accessors
    val mainContext: CoroutineContext by lazy { job.plus(mainDispatcher) }
    val logicContext: CoroutineContext by lazy { job.plus(logicDispatcher) }
    val ioContext: CoroutineContext by lazy { job.plus(ioDispatcher) }
    // endregion CoroutineContext accessors

    // region CoroutineScope accessors
    val main: CoroutineScope by lazy { CoroutineScope(mainContext) }
    val logic: CoroutineScope by lazy { CoroutineScope(logicContext) }
    val io: CoroutineScope by lazy { CoroutineScope(ioContext) }
    // endregion CoroutineScope accessors

    /**
     * Produces a new [Scopes] with a child [Job], retaining the same dispatchers.
     */
    fun createChild(isSupervisor: Boolean = false) = this + if (isSupervisor) {
        SupervisorJob(parent = job)
    } else {
        Job(parent = job)
    }

    operator fun plus(job: Job) = Scopes(
        mainDispatcher = mainDispatcher,
        logicDispatcher = logicDispatcher,
        ioDispatcher = ioDispatcher,
        job = job,
    )

    operator fun plus(coroutineContext: CoroutineContext) = this + coroutineContext.job
    operator fun plus(coroutineScope: CoroutineScope) = this + coroutineScope.coroutineContext
}

operator fun CoroutineContext.plus(scopes: Scopes) = scopes + this
operator fun CoroutineScope.plus(scopes: Scopes) = scopes + this

private const val invalidParallelismMessage = "Parallelism must be greater than 0"

/**
 * Returns a copy of this [Scopes] with limited-parallelism versions of the Logic and I/O dispatchers.
 * The [Job] is preserved.
 *
 * @param logic Maximum parallelism for the Logic dispatcher.
 * @param io Maximum parallelism for the I/O dispatcher.
 *
 * @return A new [Scopes] reflecting the specified limits, or `this` if both are `null`.
 * @throws IllegalArgumentException if any provided parallelism is <= 0.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun Scopes.byLimitingParallelism(
    logic: Int? = null,
    io: Int? = null,
): Scopes {
    if (logic == null && io == null) return this // No change
    require(logic == null || logic > 0) { invalidParallelismMessage }
    require(io == null || io > 0) { invalidParallelismMessage }
    return Scopes(
        mainDispatcher = mainDispatcher,
        logicDispatcher = logic?.let(logicDispatcher::limitedParallelism) ?: logicDispatcher,
        ioDispatcher = io?.let(ioDispatcher::limitedParallelism) ?: ioDispatcher,
        job = job,
    )
}
