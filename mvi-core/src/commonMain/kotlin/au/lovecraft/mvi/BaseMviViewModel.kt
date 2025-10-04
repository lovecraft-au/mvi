package au.lovecraft.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.lovecraft.util.LimitedSubscriberSharing
import au.lovecraft.util.Scopes
import au.lovecraft.util.WhileSubscribedAtLeast
import co.touchlab.kermit.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

typealias AnyBaseMviViewModel = BaseMviViewModel<*, *, *, *, *, *, *, *>

/**
 * Base ViewModel implementing the Model-View-Intent (MVI) pattern for predictable,
 * unidirectional state management.
 *
 * ## Overview
 *
 * This class provides a complete MVI architecture where:
 * - All UI state is represented by a single immutable [State] object
 * - State changes are triggered exclusively by [Intent]s
 * - A pure [MviReducer] function computes state transitions
 * - Views observe [viewStateFlow] for UI updates and [viewCommandFlow] for one-time events
 *
 * ## Architecture
 *
 * The MVI pattern ensures a clear separation of concerns:
 * 1. **Intents**: User actions ([UserIntent]) or internal events ([AsyncIntent]) trigger state changes
 * 2. **Reducer**: A pure function `(State, Intent) -> State` computes the next state
 * 3. **State**: Immutable data class containing all UI-relevant information
 * 4. **View State**: A simplified, view-layer representation derived via [MviViewStateMapper]
 * 5. **Commands**: Ephemeral instructions for one-time effects (navigation, dialogs, etc.)
 *
 * ## Type Parameters
 *
 * @param State The complete internal state implementing [MviState]
 * @param ViewState The view-layer representation of state for UI binding
 * @param Command Base type for all commands (both view and async)
 * @param AsyncCommand Commands that trigger internal async operations
 * @param ViewCommand Commands consumed by the view layer (navigation, toasts, etc.)
 * @param Intent Base type for all intents (both user and async)
 * @param AsyncIntent Intents triggered by internal operations or command results
 * @param UserIntent Intents triggered by user interactions
 *
 * ## Example
 *
 * ```kotlin
 * class LoginViewModel(scopes: Scopes) : BaseMviViewModel<
 *     State = LoginState,
 *     ViewState = LoginViewState,
 *     Command = LoginCommand,
 *     AsyncCommand = LoginAsyncCommand,
 *     ViewCommand = LoginViewCommand,
 *     Intent = LoginIntent,
 *     AsyncIntent = LoginAsyncIntent,
 *     UserIntent = LoginUserIntent
 * >(
 *     reducer = ::loginReducer,
 *     viewStateMapper = ::mapToViewState,
 *     asyncCommandClass = LoginAsyncCommand::class,
 *     viewCommandClass = LoginViewCommand::class,
 *     multiSubscriptionBehaviour = MultiSubscriptionBehaviour.LogError,
 *     scopes = scopes
 * ) {
 *     override fun initialState() = LoginState.initial()
 *
 *     override fun executeAsyncCommand(asyncCommand: LoginAsyncCommand) {
 *         when (asyncCommand) {
 *             is LoginAsyncCommand.AuthenticateUser -> authenticate(asyncCommand.credentials)
 *         }
 *     }
 * }
 * ```
 *
 * @see MviViewModel
 * @see MviState
 * @see MviReducer
 * @see MviViewStateMapper
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class BaseMviViewModel<
        State : MviState<State, Command>,
        ViewState : Any,
        Command : Any,
        AsyncCommand : Command,
        ViewCommand : Command,
        Intent : Any,
        AsyncIntent : Intent,
        UserIntent : Intent,
        > private constructor(
    reducer: MviReducer<State, Intent>,
    viewStateMapper: MviViewStateMapper<State, ViewState>,
    asyncCommandClass: KClass<AsyncCommand>?,
    viewCommandClass: KClass<ViewCommand>?,
    private val viewBindingSharingStarted: SharingStarted,
    scopes: Scopes,
) : ViewModel(), MviViewModel<ViewState, ViewCommand> {

    protected constructor(
        reducer: MviReducer<State, Intent>,
        viewStateMapper: MviViewStateMapper<State, ViewState>,
        asyncCommandClass: KClass<AsyncCommand>?,
        viewCommandClass: KClass<ViewCommand>?,
        multiSubscriptionBehaviour: MultiSubscriptionBehaviour,
        scopes: Scopes,
    ) : this(
        reducer = reducer,
        viewStateMapper = viewStateMapper,
        asyncCommandClass = asyncCommandClass,
        viewCommandClass = viewCommandClass,
        viewBindingSharingStarted = when (multiSubscriptionBehaviour) {
            MultiSubscriptionBehaviour.Allow -> SharingStarted.WhileSubscribed()
            MultiSubscriptionBehaviour.LogError -> LimitedSubscriberSharing.MoreThanOneLogsError
            MultiSubscriptionBehaviour.ThrowError -> LimitedSubscriberSharing.MoreThanOneThrowsError
        },
        scopes = scopes,
    )

    enum class MultiSubscriptionBehaviour {
        Allow,
        LogError,
        ThrowError,
    }

    protected val viewModelScopes: Scopes = scopes + viewModelScope.coroutineContext[Job]!!

    /**
     * Channel for async intents, typically triggered by internal operations.
     */
    private val asyncIntentChannel: Channel<AsyncIntent> = mviAsyncIntentChannel()

    /**
     * Channel for user intents, typically triggered by UI interactions.
     */
    private val userIntentChannel: Channel<UserIntent> = mviUserIntentChannel()

    /**
     * Sends an async intent for processing.
     * Subclasses should call this method when programmatic state changes are needed.
     */
    protected fun onAsyncIntent(intent: AsyncIntent) = viewModelScopes.logic.launch {
        asyncIntentChannel.send(intent)
    }

    /**
     * Sends a user intent for processing.
     * Subclasses should call this method in response to UI events.
     */
    protected fun onUserIntent(intent: UserIntent) {
        if (userIntentChannel.trySend(intent).isFailure) {
            Logger.e { "User intent channel is full; dropping intent: $intent" }
        }
    }

    /**
     * Provides the initial state for this ViewModel.
     * Called once when the view first subscribes.
     */
    protected abstract fun initialState(): State

    /**
     * Lazily initialised initial state, used as the seed value for state flows.
     */
    private val initialState: State by lazy {
        initialState()
    }

    /**
     * Internal wrapper to differentiate state emissions when commands are repeated.
     */
    private data class DifferentiatedState<State>(
        val state: State,
        val heartbeat: Boolean,
    )

    /**
     * Internal state flow from which [viewStateFlow] and [viewCommandFlow] are derived.
     *
     * This flow merges async and user intents, applies the reducer function to produce
     * new states, and handles repeated commands using a heartbeat mechanism to ensure
     * commands are re-emitted even when identical to previous commands.
     */
    private val stateFlow: StateFlow<State> by lazy {

        val intentFlow: Flow<Intent> = merge(
            asyncIntentChannel.consumeAsFlow(),
            userIntentChannel.consumeAsFlow(),
        )

        intentFlow
            .scan(
                initial = DifferentiatedState(
                    state = initialState,
                    heartbeat = false
                )
            ) { differentiatedState: DifferentiatedState<State>, intent: Intent ->
                val noCommandState = differentiatedState.state.byClearingCommands()
                val nextState = reducer(noCommandState, intent)

                val hasNewCommands = nextState.commands.isNotEmpty()
                DifferentiatedState(
                    state = nextState,
                    heartbeat = if (hasNewCommands) {
                        // Toggle heartbeat to force emission even if commands are identical
                        !differentiatedState.heartbeat
                    } else {
                        // Preserve heartbeat to prevent unnecessary emissions
                        differentiatedState.heartbeat
                    }
                )
            }
            .distinctUntilChanged()
            .map { differentiatedState -> differentiatedState.state }
            .catch { error -> Logger.e("MVI flow error: ${this::class.simpleName}", error) }
            .stateIn(
                scope = viewModelScopes.logic,
                started = viewBindingSharingStarted,
                initialValue = initialState,
            )
    }

    /**
     * View-layer state flow derived from the internal state via [viewStateMapper].
     *
     * This is the primary state observable that UI components should bind to.
     */
    final override val viewStateFlow: StateFlow<ViewState> by lazy {
        stateFlow
            .map(viewStateMapper)
            .stateIn(
                scope = viewModelScopes.logic,
                started = viewBindingSharingStarted,
                initialValue = viewStateMapper(initialState),
            )
    }

    /**
     * Internal command flow shared between [viewCommandFlow] and [asyncCommandFlow].
     *
     * Waits for both consumers to subscribe before starting, ensuring that both
     * view commands and async commands receive all emissions.
     */
    private val commandFlow: Flow<Command> by lazy {
        stateFlow
            .flatMapConcat { state -> state.commands.asFlow() }
            .shareIn(
                scope = viewModelScopes.logic,
                started = SharingStarted.WhileSubscribedAtLeast(minSubscribers = 2),
                replay = 0,
            )
    }

    /**
     * Stream of one-time commands for the view layer to execute.
     *
     * Examples include navigation events, sounds, or haptic feedback.
     */
    final override val viewCommandFlow: SharedFlow<ViewCommand> by lazy {
        if (viewCommandClass == null) {
            emptyFlow<ViewCommand>()
        } else {
            commandFlow.filterIsInstance<ViewCommand>(viewCommandClass)
        }.shareIn(
            scope = viewModelScopes.logic,
            started = SharingStarted.Eagerly,
            replay = 0,
        )
    }

    /**
     * Stream of commands that trigger internal async operations.
     *
     * If [asyncCommandClass] is null, this flow remains empty.
     */
    private val asyncCommandFlow: Flow<AsyncCommand> by lazy {
        if (asyncCommandClass == null) {
            emptyFlow()
        } else {
            commandFlow.filterIsInstance<AsyncCommand>(asyncCommandClass)
        }
    }

    /**
     * Initializes the async command processing pipeline.
     *
     * Commands are collected and passed to [executeAsyncCommand].
     * Errors are caught to prevent a single command failure from stopping the flow.
     */
    init {
        viewModelScopes.logic.launch {
            asyncCommandFlow
                .catch { error ->
                    Logger.e(
                        messageString = "Async command flow error: ${this@BaseMviViewModel::class.simpleName}",
                        throwable = error
                    )
                }
                .collect(::executeAsyncCommand)
        }
    }

    /**
     * Executes an async command.
     *
     * Override this method in subclasses to handle async commands.
     * Default implementation does nothing, allowing ViewModels that don't use async commands.
     */
    protected open fun executeAsyncCommand(asyncCommand: AsyncCommand) = Unit
}
