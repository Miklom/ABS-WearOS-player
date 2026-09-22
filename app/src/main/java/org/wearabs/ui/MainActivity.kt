package org.wearabs.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.input.RemoteInputIntentHelper
import org.wearabs.WearAbsApp

/** Opens Wear's text input for one field and reports the result back. */
typealias TextInputLauncher = (label: String, initial: String, onResult: (String) -> Unit) -> Unit

class MainActivity : ComponentActivity() {

    /** Set right before the text-input activity is launched. */
    private var onTextEntered: ((String) -> Unit)? = null

    private val textInput = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = onTextEntered
        onTextEntered = null
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val text = RemoteInput.getResultsFromIntent(data)
            ?.getCharSequence(REMOTE_INPUT_KEY)
            ?.toString()
            .orEmpty()
        if (text.isNotBlank()) callback?.invoke(text)
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Downloads still work without it; only the progress notification is lost. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // minSdk is 33, so this permission always needs asking for.
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val launchTextInput: TextInputLauncher = { label, initial, onResult ->
            onTextEntered = onResult
            textInput.launch(buildTextInputIntent(label, initial))
        }

        setContent {
            MaterialTheme {
                AppScaffold {
                    WearAbsNavHost(launchTextInput)
                }
            }
        }
    }

    /**
     * Wear's system text input: voice, the on-watch keyboard, and — when a phone
     * is paired — the phone keyboard, all behind one RemoteInput intent.
     *
     * Note that Wear has no masked variant of this, so a password is visible
     * while it is being typed.
     */
    private fun buildTextInputIntent(label: String, initial: String): Intent {
        val remoteInputs = listOf(
            RemoteInput.Builder(REMOTE_INPUT_KEY)
                .setLabel(label)
                // Offers the current value as a one-tap choice, which is as close
                // to pre-filling as Wear's text input gets.
                .apply { if (initial.isNotEmpty()) setChoices(arrayOf<CharSequence>(initial)) }
                .build()
        )
        return RemoteInputIntentHelper.createActionRemoteInputIntent().also { intent ->
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, remoteInputs)
        }
    }

    private companion object {
        const val REMOTE_INPUT_KEY = "wearabs_text"
    }
}

@Composable
private fun WearAbsNavHost(launchTextInput: TextInputLauncher) {
    val navController = rememberSwipeDismissableNavController()
    val session by WearAbsApp.container().authStore.session.collectAsStateWithLifecycle()

    // The whole app hangs off whether there is a session: logging in moves to
    // Home, and a rejected refresh token drops straight back to Login.
    LaunchedEffect(session != null) {
        val target = if (session != null) Routes.HOME else Routes.LOGIN
        if (navController.currentDestination?.route != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
        }
    }

    SwipeDismissableNavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) { SplashScreen() }

        composable(Routes.LOGIN) {
            val model: LoginViewModel = viewModel()
            LoginScreen(
                viewModel = model,
                onEditField = launchTextInput
            )
        }

        composable(Routes.HOME) {
            val model: HomeViewModel = viewModel()
            val tiles by model.tiles.collectAsStateWithLifecycle()
            HomeScreen(
                tiles = tiles,
                onSearch = { navController.navigate(Routes.SEARCH) },
                onBook = { navController.navigate(Routes.book(it)) },
                onSignOut = model::signOut
            )
        }

        composable(Routes.SEARCH) {
            val model: SearchViewModel = viewModel()
            SearchScreen(
                viewModel = model,
                onLaunchInput = {
                    launchTextInput("Search books", "") { query -> model.search(query) }
                },
                onBook = { navController.navigate(Routes.book(it.itemId)) }
            )
        }

        composable(
            Routes.BOOK,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType })
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            val model: BookViewModel = viewModel(
                key = "book-$itemId",
                factory = viewModelFactory { application -> BookViewModel(application, itemId) }
            )
            BookScreen(
                viewModel = model,
                onPlay = { navController.navigate(Routes.player(itemId)) }
            )
        }

        composable(
            Routes.PLAYER,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType })
        ) { entry ->
            val itemId = entry.arguments?.getString("itemId").orEmpty()
            val model: PlayerViewModel = viewModel(
                key = "player-$itemId",
                factory = viewModelFactory { application -> PlayerViewModel(application, itemId) }
            )
            PlayerScreen(viewModel = model)
        }
    }
}

/** Minimal factory so ViewModels can take the item id without a DI framework. */
@Composable
private fun viewModelFactory(create: (Application) -> ViewModel): ViewModelProvider.Factory {
    val application = LocalContext.current.applicationContext as Application
    return remember(application, create) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                create(application) as T
        }
    }
}
