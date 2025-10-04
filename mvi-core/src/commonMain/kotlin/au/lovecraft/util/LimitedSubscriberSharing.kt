package au.lovecraft.util

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.*

/**
 * A [SharingStarted] strategy that enforces a maximum number of concurrent subscribers.
 *
 * Useful when a higher subscriber count likely signals a bug (e.g. duplicated UI bindings
 * to state/command flows in MVI/MVVM architectures).
 */
class LimitedSubscriberSharing(
    private val maximumSubscriberCount: Int,
    private val excessionHandler: () -> Unit,
) : SharingStarted {

    companion object {
        private const val MORE_THAN_ONE_SUBSCRIBER_MESSAGE = "More than one flow subscription detected!"

        /**
         * Variant that throws an error when the host [Flow] transitions to more than one subscriber.
         *
         * Useful during development to fail fast when duplicate subscriptions indicate a bug.
         */
        val MoreThanOneThrowsError: LimitedSubscriberSharing by lazy {
            LimitedSubscriberSharing(maximumSubscriberCount = 1) {
                error(MORE_THAN_ONE_SUBSCRIBER_MESSAGE)
            }
        }

        /**
         * Variant that logs an error when the host [Flow] transitions to more than one subscriber.
         *
         * Useful in production to surface issues without crashing the application.
         */
        val MoreThanOneLogsError: LimitedSubscriberSharing by lazy {
            LimitedSubscriberSharing(maximumSubscriberCount = 1) {
                Logger.e(MORE_THAN_ONE_SUBSCRIBER_MESSAGE)
            }
        }
    }

    override fun command(subscriptionCount: StateFlow<Int>): Flow<SharingCommand> = flow {
        data class State(val count: Int, val command: SharingCommand)

        val initialState = State(count = 0, command = SharingCommand.STOP)
        subscriptionCount
            .scan(initialState) { state: State, newCount: Int ->
                if (newCount > maximumSubscriberCount) {
                    excessionHandler()
                }
                when {
                    /** On rising from zero subscriptions to a positive number, start sharing */
                    (state.count == 0) && (newCount > 0) -> State(newCount, SharingCommand.START)

                    /** On dropping back to zero subscriptions, stop sharing */
                    (state.count > 0) && (newCount == 0) -> State(newCount, SharingCommand.STOP_AND_RESET_REPLAY_CACHE)

                    /**
                     * Otherwise, maintain the current command,
                     * which will not be re-emitted due to the [distinctUntilChanged] usage below.
                     */
                    else -> state.copy(count = newCount)
                }
            }
            .map { state: State -> state.command }
            .distinctUntilChanged<SharingCommand>()
    }
}
