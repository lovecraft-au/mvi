package au.lovecraft.mvi

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Exposes a view-focused state to bind to and a stream of one-off commands
 * that the view should execute (e.g., navigation, sound, vibration).
 */
interface MviViewModel<ViewState : Any, ViewCommand : Any> {
    val viewStateFlow: StateFlow<ViewState>
    val viewCommandFlow: SharedFlow<ViewCommand>
}
