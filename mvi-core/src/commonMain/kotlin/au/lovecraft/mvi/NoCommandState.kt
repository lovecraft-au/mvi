package au.lovecraft.mvi

interface NoCommandState<State : MviState<State, Command>, Command : Any> : MviState<State, Command> {
    override val commands: List<Nothing> get() = emptyList()

    @Suppress("UNCHECKED_CAST")
    override fun bySettingCommands(commands: List<Command>): State = this as State
}
