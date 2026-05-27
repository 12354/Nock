package app.nock.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.nock.android.R
import app.nock.android.ui.edit.EditReminderRoute
import app.nock.android.ui.group.GroupEditorScreen
import app.nock.android.ui.reminders.RemindersScreen
import app.nock.android.ui.settings.SettingsScreen
import app.nock.android.ui.today.TodayScreen

sealed class Tab(val route: String, @StringRes val labelRes: Int, val icon: ImageVector) {
    object Today : Tab("today", R.string.today, Icons.Filled.Today)
    object Reminders : Tab("reminders", R.string.reminders, Icons.Filled.Checklist)
    object Settings : Tab("settings", R.string.settings, Icons.Filled.Settings)
}

const val EXTRA_OPEN_TAB = "app.nock.android.ui.extra.OPEN_TAB"

private val TABS = listOf(Tab.Today, Tab.Reminders, Tab.Settings)

@Composable
fun NockApp(
    initialTab: String = Tab.Today.route,
    requestedTab: String? = null,
    onTabConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    LaunchedEffect(requestedTab) {
        val target = requestedTab ?: return@LaunchedEffect
        nav.navigate(target) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        onTabConsumed()
    }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            val showBottomBar = TABS.any { it.route == currentRoute } || currentRoute == null
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = initialTab,
            modifier = Modifier.padding(padding)
        ) {
            composable(Tab.Today.route) {
                TodayScreen(
                    onAddReminder = { nav.navigate("edit?id=0") },
                    onEditReminder = { id -> nav.navigate("edit?id=$id") }
                )
            }
            composable(Tab.Reminders.route) {
                RemindersScreen(
                    onAddReminder = { nav.navigate("edit?id=0") },
                    onEditReminder = { id -> nav.navigate("edit?id=$id") },
                    onEditGroup = { id -> nav.navigate("group?id=$id") }
                )
            }
            composable(Tab.Settings.route) {
                SettingsScreen(
                    onEditGroup = { id -> nav.navigate("group?id=$id") }
                )
            }
            composable("edit?id={id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                EditReminderRoute(
                    reminderId = id,
                    onDone = { nav.popBackStack() }
                )
            }
            composable("group?id={id}") { entry ->
                val id = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L
                GroupEditorScreen(
                    groupId = id,
                    onDone = { nav.popBackStack() }
                )
            }
        }
    }
}
