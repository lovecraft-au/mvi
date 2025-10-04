package au.lovecraft.mvi

/**
 * Reducers are pure functions that take the current 'state' plus an 'intent', and return the new 'state'.
 */
typealias MviReducer<State, Intent> = (State, Intent) -> State
