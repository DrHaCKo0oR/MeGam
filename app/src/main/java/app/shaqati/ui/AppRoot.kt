package app.shaqati.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.shaqati.R
import app.shaqati.core.model.Source
import app.shaqati.ui.check.SiteCheckScreen
import app.shaqati.ui.detail.DetailScreen
import app.shaqati.ui.detail.PhotoViewer
import app.shaqati.ui.feed.FeedScreen
import app.shaqati.ui.map.MapScreen
import app.shaqati.ui.onboarding.OnboardingScreen
import app.shaqati.ui.saved.HiddenScreen
import app.shaqati.ui.saved.SavedScreen
import app.shaqati.ui.settings.LocationPickerScreen
import app.shaqati.ui.settings.SettingsScreen

private data class Tab(val label: Int, val selected: ImageVector, val unselected: ImageVector, val tag: String)

private val TABS = listOf(
    Tab(R.string.tab_feed, Icons.Filled.Home, Icons.Outlined.Home, "tab_feed"),
    Tab(R.string.tab_map, Icons.Filled.Map, Icons.Outlined.Map, "tab_map"),
    Tab(R.string.tab_saved, Icons.Filled.Favorite, Icons.Outlined.FavoriteBorder, "tab_saved"),
    Tab(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings"),
)

@Composable
fun AppRoot(vm: AppViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    val nav = rememberNavController()
    val openDetail: (String) -> Unit = { id -> nav.navigate("detail/" + Uri.encode(id)) }
    NavHost(nav, startDestination = if (s.onboarded) "home" else "onboarding") {
        composable("onboarding") {
            OnboardingScreen(
                vm,
                onPickWork = { nav.navigate("pick/work") },
                onDone = { nav.navigate("home") { popUpTo("onboarding") { inclusive = true } } },
            )
        }
        composable("home") {
            HomeScreen(
                vm, openDetail,
                openPicker = { nav.navigate("pick/$it") },
                openHidden = { nav.navigate("hidden") },
                openCheck = { nav.navigate("check/${it.key}") },
            )
        }
        composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            DetailScreen(
                vm, id,
                onBack = { nav.popBackStack() },
                openPhotos = { index -> nav.navigate("photos/" + Uri.encode(id) + "/" + index) },
            )
        }
        composable(
            "photos/{id}/{index}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("index") { type = NavType.IntType }),
        ) { entry ->
            PhotoViewer(vm, entry.arguments?.getString("id").orEmpty(), entry.arguments?.getInt("index") ?: 0, onClose = { nav.popBackStack() })
        }
        composable("pick/{target}", arguments = listOf(navArgument("target") { type = NavType.StringType })) { entry ->
            LocationPickerScreen(vm, entry.arguments?.getString("target") ?: "work", onDone = { nav.popBackStack() })
        }
        composable("hidden") {
            HiddenScreen(vm, onBack = { nav.popBackStack() }, openDetail = openDetail)
        }
        composable("check/{source}", arguments = listOf(navArgument("source") { type = NavType.StringType })) { entry ->
            val source = Source.fromKey(entry.arguments?.getString("source").orEmpty()) ?: Source.BAYUT
            SiteCheckScreen(vm, source, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun HomeScreen(
    vm: AppViewModel,
    openDetail: (String) -> Unit,
    openPicker: (String) -> Unit,
    openHidden: () -> Unit,
    openCheck: (Source) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(if (tab == i) t.selected else t.unselected, null) },
                        label = { Text(stringResource(t.label)) },
                        modifier = Modifier.testTag(t.tag),
                    )
                }
            }
        },
    ) { padding: PaddingValues ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            when (tab) {
                0 -> FeedScreen(vm, openDetail, openCheck)
                1 -> MapScreen(vm, openDetail)
                2 -> SavedScreen(vm, openDetail)
                else -> SettingsScreen(vm, openPicker, openHidden, openCheck)
            }
        }
    }
}
