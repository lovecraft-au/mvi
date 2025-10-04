package au.lovecraft.mvi.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import au.lovecraft.mvi.MviViewModel

@Composable
fun <ViewState : Any, ViewCommand : Any> MviView(
    viewModel: MviViewModel<ViewState, ViewCommand>,
    onViewCommand: (ViewCommand) -> Unit,
    content: @Composable (viewState: ViewState) -> Unit,
) {
    LaunchedEffect(viewModel) {
        viewModel.viewCommandFlow.collect { viewCommand ->
            onViewCommand(viewCommand)
        }
    }
    val viewState: ViewState by viewModel.viewStateFlow.collectAsState()
    content(viewState)
}
