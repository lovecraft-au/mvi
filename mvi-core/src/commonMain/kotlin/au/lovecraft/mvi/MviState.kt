package au.lovecraft.mvi

/**
 * Defines an MVI state that can carry ephemeral commands for the view layer,
 * such as navigation, sound, or haptic feedback.
 */
interface MviState<Self : MviState<Self, Command>, Command : Any> {

    /**
     * Commands emitted as a result of entering this state.
     * Commands are transient and should not be carried across subsequent state copies.
     */
    val commands: List<Command>

    fun bySettingCommands(commands: List<Command>): Self
}

fun <Self : MviState<Self, Command>, Command : Any> MviState<Self,Command>.byClearingCommands(): Self =
    bySettingCommands(emptyList())

fun <Self : MviState<Self, Command>, Command : Any> MviState<Self,Command>.byAddingCommand(command: Command): Self =
    bySettingCommands(commands + command)

operator fun <Self : MviState<Self, Command>, Command : Any> MviState<Self,Command>.plus(command: Command): Self =
    byAddingCommand(command)
