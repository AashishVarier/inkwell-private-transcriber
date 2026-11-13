package com.variableduck.inkwell.transcriber.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.variableduck.inkwell.transcriber.ui.library.LibraryScreen
import com.variableduck.inkwell.transcriber.ui.models.ModelsScreen
import com.variableduck.inkwell.transcriber.ui.note.NoteScreen
import com.variableduck.inkwell.transcriber.ui.settings.SettingsScreen

/** Screens, encoded as strings so the back stack survives configuration changes. */
object Routes {
    const val LIBRARY = "library"
    const val MODELS = "models"
    const val SETTINGS = "settings"
    fun note(id: String) = "note/$id"
}

class Navigator(private val push: (String) -> Unit, private val pop: () -> Unit) {
    fun open(route: String) = push(route)
    fun back() = pop()
}

/**
 * Gives each open note its own ViewModelStore, cleared when the note is closed, so a note's
 * ViewModel (its text, player and collectors) doesn't live on for the rest of the session.
 * This holder is a ViewModel of the activity, so note ViewModels still survive rotation.
 * The other screens keep using the activity's store, as before.
 */
class RouteStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    fun ownerFor(route: String): ViewModelStoreOwner {
        val store = stores.getOrPut(route) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    /** Clears the ViewModels of routes that are no longer on [stack]. */
    fun retainOnly(stack: Collection<String>) {
        val keep = stack.toSet()
        stores.keys.filter { it !in keep }.forEach { stores.remove(it)?.clear() }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

@Composable
fun InkwellNavHost() {
    var stack by rememberSaveable(stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })) {
        mutableStateOf(listOf(Routes.LIBRARY))
    }
    val routeStores: RouteStores = viewModel { RouteStores() }
    val navigator = Navigator(
        push = { route -> if (stack.last() != route) stack = stack + route },
        pop = {
            if (stack.size > 1) {
                stack = stack.dropLast(1)
                routeStores.retainOnly(stack)
            }
        },
    )
    BackHandler(enabled = stack.size > 1) { navigator.back() }

    val route = stack.last()
    when {
        route == Routes.MODELS -> ModelsScreen(navigator)
        route == Routes.SETTINGS -> SettingsScreen(navigator)
        route.startsWith("note/") -> {
            val owner = remember(routeStores, route) { routeStores.ownerFor(route) }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                NoteScreen(route.removePrefix("note/"), navigator)
            }
        }
        else -> LibraryScreen(navigator)
    }
}
