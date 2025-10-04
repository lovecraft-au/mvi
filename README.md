# MVI Architecture Library (Kotlin Multiplatform)

A Kotlin Multiplatform library implementing the Model-View-Intent (MVI) pattern for predictable, testable, and maintainable UI state management.

This library provides a complete MVI architecture with opinionated implementations designed to handle complex presentation logic while maintaining 100% unit testability.

## Why MVI?

The Model-View-Intent pattern offers several advantages for modern application development:

- **Predictability**: Unidirectional data flow eliminates ambiguity about how state changes
- **Debuggability**: Every state transition is explicit and logged, making issues easy to trace
- **Testability**: Pure reducer functions are trivial to test without mocking
- **Maintainability**: Complex UI logic remains comprehensible as it grows
- **Time travel debugging**: State history can be captured and replayed

### Trade-offs

**Screens written with this library tend to be verbose.** This is a deliberate trade-off. The verbosity comes from:

- Explicit intent definitions for every user action
- Comprehensive state modeling with all possible UI states
- Separation of concerns between view state, commands, and intents

However, this verbosity makes sense when you optimize for:

- **Handling highly complex presentation logic** with multiple async operations, conditional flows, and edge cases
- **Complete unit testability** of all business logic without framework dependencies
- **Team collaboration** where explicit contracts between layers prevent confusion
- **Long-term maintenance** where clarity trumps brevity

If your screens are simple forms with minimal logic, this pattern may be overkill. But for complex user flows (multi-step wizards, real-time updates, intricate validation), the structure pays dividends.

## Core Concepts

### Architecture Overview

```
User Action → Intent → Reducer → New State → View Update
                  ↓
            Async Command → executeAsyncCommand() → Async Intent → Reducer → ...
```

The library enforces a strict unidirectional data flow:

1. **Intents** represent all possible events (user actions or async results)
2. **Reducer** is a pure function `(State, Intent) -> State` that computes state transitions
3. **State** is an immutable data class containing all UI-relevant information
4. **View State** is a simplified representation of state, optimized for UI binding
5. **Commands** are ephemeral instructions for one-time effects (navigation, toasts, etc.)

### Key Opinions

#### 1. Separation of User and Async Intents

The library distinguishes between:
- **User Intents**: Direct user actions (button clicks, text input)
- **Async Intents**: Internal events (network responses, timer ticks)

This separation provides different backpressure strategies:
- User intents drop latest to maintain UI responsiveness
- Async intents suspend to preserve ordering and reliability

#### 2. Commands for Side Effects

Commands represent one-time effects that shouldn't be modeled as persistent state:
- **View Commands**: Navigation, toasts, haptic feedback
- **Async Commands**: Trigger background operations

Commands are automatically cleared after emission to prevent re-execution on configuration changes.

#### 3. Pure Reducer Functions

Reducers must be pure: same inputs always produce the same output. This makes:
- Unit testing trivial (no mocking required)
- Debugging deterministic (replay any state transition)
- Reasoning about logic straightforward

#### 4. Explicit Async State Modeling

The `Async<T>` type makes "loading" states explicit:
```kotlin
sealed interface Async<out T> {
    data object Determining : Async<Nothing>
    value class Determined<T>(val value: T) : Async<T>
}
```

This eliminates ambiguous `null` values and makes async operations visible in the type system.

#### 5. Built-in Subscription Safety

The library can detect and prevent multiple subscriptions to state flows, catching UI binding bugs during development:
```kotlin
multiSubscriptionBehaviour = MultiSubscriptionBehaviour.ThrowError
```

## Installation

This library is not yet published to a public repository. Recommended consumption methods:

### Option 1: Git Submodule

```bash
git submodule add https://github.com/yourorg/mvi.git libs/mvi
```

Then in `settings.gradle.kts`:
```kotlin
includeBuild("libs/mvi")
```

### Option 2: Included Build

Clone the repository locally and reference it:

`settings.gradle.kts`:
```kotlin
includeBuild("../path/to/mvi")
```

Then in your module's `build.gradle.kts`:
```kotlin
dependencies {
    implementation("au.lovecraft:mvi")
}
```

## Quick Start

### 1. Define Your State and Intents

```kotlin
// Complete internal state
data class LoginState(
    val username: String = "",
    val password: String = "",
    val loginResult: Async<Boolean> = Async.Determining,
    override val commands: List<LoginCommand> = emptyList()
) : MviState<LoginState, LoginCommand> {
    override fun byClearingCommands() = copy(commands = emptyList())
}

// Intents represent all possible events
sealed interface LoginIntent
sealed interface LoginUserIntent : LoginIntent {
    data class UsernameChanged(val value: String) : LoginUserIntent
    data class PasswordChanged(val value: String) : LoginUserIntent
    data object LoginButtonClicked : LoginUserIntent
}
sealed interface LoginAsyncIntent : LoginIntent {
    data class LoginCompleted(val success: Boolean) : LoginAsyncIntent
}

// Commands for one-time effects
sealed interface LoginCommand
sealed interface LoginAsyncCommand : LoginCommand {
    data class AuthenticateUser(val username: String, val password: String) : LoginAsyncCommand
}
sealed interface LoginViewCommand : LoginCommand {
    data object NavigateToHome : LoginViewCommand
    data object ShowInvalidCredentialsError : LoginViewCommand
}
```

### 2. Implement the Reducer

```kotlin
fun loginReducer(state: LoginState, intent: LoginIntent): LoginState = when (intent) {
    is LoginUserIntent.UsernameChanged -> state.copy(username = intent.value)
    
    is LoginUserIntent.PasswordChanged -> state.copy(password = intent.value)
    
    is LoginUserIntent.LoginButtonClicked -> state.copy(
        loginResult = Async.Determining,
        commands = listOf(LoginAsyncCommand.AuthenticateUser(state.username, state.password))
    )
    
    is LoginAsyncIntent.LoginCompleted -> state.copy(
        loginResult = Async.Determined(intent.success),
        commands = if (intent.success) {
            listOf(LoginViewCommand.NavigateToHome)
        } else {
            listOf(LoginViewCommand.ShowInvalidCredentialsError)
        }
    )
}
```

### 3. Create the ViewModel

```kotlin
class LoginViewModel(
    private val authRepository: AuthRepository,
    scopes: Scopes
) : BaseMviViewModel<
    State = LoginState,
    ViewState = LoginViewState,
    Command = LoginCommand,
    AsyncCommand = LoginAsyncCommand,
    ViewCommand = LoginViewCommand,
    Intent = LoginIntent,
    AsyncIntent = LoginAsyncIntent,
    UserIntent = LoginUserIntent
>(
    reducer = ::loginReducer,
    viewStateMapper = { state -> LoginViewState(
        username = state.username,
        password = state.password,
        isLoading = state.loginResult is Async.Determining,
        canSubmit = state.username.isNotBlank() && state.password.isNotBlank()
    ) },
    asyncCommandClass = LoginAsyncCommand::class,
    viewCommandClass = LoginViewCommand::class,
    multiSubscriptionBehaviour = MultiSubscriptionBehaviour.LogError,
    scopes = scopes
) {
    override fun initialState() = LoginState()
    
    override fun executeAsyncCommand(asyncCommand: LoginAsyncCommand) {
        when (asyncCommand) {
            is LoginAsyncCommand.AuthenticateUser -> {
                viewModelScopes.io.launch {
                    val success = authRepository.authenticate(
                        asyncCommand.username,
                        asyncCommand.password
                    )
                    onAsyncIntent(LoginAsyncIntent.LoginCompleted(success))
                }
            }
        }
    }
    
    // Public API for the view layer
    fun onUsernameChanged(username: String) = 
        onUserIntent(LoginUserIntent.UsernameChanged(username))
    
    fun onPasswordChanged(password: String) = 
        onUserIntent(LoginUserIntent.PasswordChanged(password))
    
    fun onLoginClicked() = 
        onUserIntent(LoginUserIntent.LoginButtonClicked)
}
```

### 4. Bind to the UI

```kotlin
// Android Compose example
@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val viewState by viewModel.viewStateFlow.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.viewCommandFlow.collect { command ->
            when (command) {
                LoginViewCommand.NavigateToHome -> navController.navigate("home")
                LoginViewCommand.ShowInvalidCredentialsError -> {
                    snackbarHost.showSnackbar("Invalid credentials")
                }
            }
        }
    }
    
    Column {
        TextField(
            value = viewState.username,
            onValueChange = viewModel::onUsernameChanged,
            label = { Text("Username") }
        )
        
        TextField(
            value = viewState.password,
            onValueChange = viewModel::onPasswordChanged,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation()
        )
        
        Button(
            onClick = viewModel::onLoginClicked,
            enabled = viewState.canSubmit && !viewState.isLoading
        ) {
            if (viewState.isLoading) {
                CircularProgressIndicator()
            } else {
                Text("Login")
            }
        }
    }
}
```

### 5. Unit Test Everything

```kotlin
class LoginReducerTest {
    @Test
    fun `username change updates state`() {
        val state = LoginState()
        val newState = loginReducer(state, LoginUserIntent.UsernameChanged("alice"))
        
        assertEquals("alice", newState.username)
        assertEquals(emptyList(), newState.commands)
    }
    
    @Test
    fun `login button click triggers auth command`() {
        val state = LoginState(username = "alice", password = "secret")
        val newState = loginReducer(state, LoginUserIntent.LoginButtonClicked)
        
        assertEquals(Async.Determining, newState.loginResult)
        assertEquals(
            listOf(LoginAsyncCommand.AuthenticateUser("alice", "secret")),
            newState.commands
        )
    }
    
    @Test
    fun `successful login navigates to home`() {
        val state = LoginState(loginResult = Async.Determining)
        val newState = loginReducer(state, LoginAsyncIntent.LoginCompleted(success = true))
        
        assertEquals(Async.Determined(true), newState.loginResult)
        assertEquals(listOf(LoginViewCommand.NavigateToHome), newState.commands)
    }
    
    @Test
    fun `failed login shows error`() {
        val state = LoginState(loginResult = Async.Determining)
        val newState = loginReducer(state, LoginAsyncIntent.LoginCompleted(success = false))
        
        assertEquals(Async.Determined(false), newState.loginResult)
        assertEquals(listOf(LoginViewCommand.ShowInvalidCredentialsError), newState.commands)
    }
}
```

## Features

### Async State Management

The `Async<T>` type provides explicit modeling of asynchronous operations:

```kotlin
data class ProfileState(
    val userData: Async<User> = Async.Determining,
    // ...
) : MviState<ProfileState, ProfileCommand>

// In your UI
when (viewState.userData) {
    Async.Determining -> LoadingIndicator()
    is Async.Determined -> UserProfile(viewState.userData.value)
}
```

### Structured Concurrency

The `Scopes` helper bundles coroutine dispatchers for consistent concurrency management:

```kotlin
val scopes = Scopes(
    mainDispatcher = Dispatchers.Main,
    logicDispatcher = Dispatchers.Default,
    ioDispatcher = Dispatchers.IO
)

// In ViewModel
viewModelScopes.io.launch { /* I/O work */ }
viewModelScopes.logic.launch { /* CPU work */ }
```

### Intent Channel Configuration

User and async intents have different backpressure strategies:

- **Async intents**: Rendezvous channel with suspension (preserves ordering)
- **User intents**: Capacity 1 with DROP_LATEST (maintains responsiveness)

This ensures the UI remains responsive under load while internal operations maintain correctness.

### Command Deduplication

Commands are automatically cleared between state emissions, but repeated commands are supported via a heartbeat mechanism. This ensures navigation commands always fire, even if they're identical to previous commands.

## Supported Platforms

Configured for Kotlin Multiplatform:
- Android
- JVM
- iOS
- (Add additional targets as needed)

## When to Use This Library

### Good Fit

- Complex user flows with multiple steps and async operations
- Applications requiring high testability (fintech, healthcare)
- Teams collaborating on large codebases
- Real-time features with concurrent state updates
- Legacy code migrations seeking structure

### Consider Alternatives

- Simple CRUD screens with minimal logic
- Prototypes prioritizing speed over structure
- Single-developer projects with simple requirements

## Building

Requirements:
- Kotlin 2.x
- Gradle 8.x

```bash
./gradlew build
./gradlew test
```

## Roadmap

- Publish artifacts to Maven Central
- Add sample applications demonstrating complex flows
- Provide Android Studio / IntelliJ IDEA templates
- Create migration guides from other patterns (MVVM, MVC)

## License

This software is released under the LGPL License.
See [LICENSE.md](LICENSE.md) for details.
