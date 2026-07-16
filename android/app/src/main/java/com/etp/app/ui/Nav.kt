package com.etp.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.etp.app.EtpApplication
import com.etp.app.data.SessionState
import com.etp.app.data.User
import com.etp.app.ui.screens.AuthScreen
import com.etp.app.ui.screens.CheckoutScreen
import com.etp.app.ui.screens.CreateEventScreen
import com.etp.app.ui.screens.DashboardScreen
import com.etp.app.ui.screens.EditEventScreen
import com.etp.app.ui.screens.EventDetailScreen
import com.etp.app.ui.screens.HomeScreen
import com.etp.app.ui.screens.NotificationsScreen
import com.etp.app.ui.screens.OrderResultScreen
import com.etp.app.ui.screens.ProfileScreen
import com.etp.app.ui.screens.ScannerScreen
import com.etp.app.ui.screens.TicketDetailScreen
import com.etp.app.ui.screens.TicketsScreen
import com.etp.app.ui.theme.BrandGradient

@Composable
fun RootNav() {
    val app = LocalContext.current.applicationContext as EtpApplication
    val session by app.container.session.state.collectAsState()

    Crossfade(targetState = session, label = "session") { state ->
        when (state) {
            is SessionState.Loading -> SplashScreen()
            is SessionState.LoggedOut -> AuthScreen()
            is SessionState.LoggedIn -> LoggedInNav(state.user)
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(BrandGradient),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.ConfirmationNumber,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(72.dp),
            )
            Text(
                "Eventix",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun LoggedInNav(user: User) {
    val nav = rememberNavController()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScaffold(
                user = user,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onEventClick = { id -> nav.navigate("event/$id") },
                onOpenTicket = { id -> nav.navigate("ticket/$id") },
                onOpenNotifications = { nav.navigate("notifications") },
                onOpenProfile = { nav.navigate("profile") },
            )
        }
        composable(
            "event/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            EventDetailScreen(
                eventId = entry.arguments?.getLong("id") ?: 0L,
                user = user,
                onBack = { nav.popBackStack() },
                onCheckout = { tierId, qty, offerId ->
                    nav.navigate("checkout/${entry.arguments?.getLong("id")}/$tierId/$qty?offerId=${offerId ?: -1L}")
                },
                onManage = { id -> nav.navigate("dashboard/$id") },
            )
        }
        composable(
            "checkout/{eventId}/{tierId}/{qty}?offerId={offerId}",
            arguments = listOf(
                navArgument("eventId") { type = NavType.LongType },
                navArgument("tierId") { type = NavType.LongType },
                navArgument("qty") { type = NavType.IntType },
                navArgument("offerId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            val offerId = entry.arguments?.getLong("offerId")?.takeIf { it > 0 }
            CheckoutScreen(
                eventId = entry.arguments?.getLong("eventId") ?: 0L,
                tierId = entry.arguments?.getLong("tierId") ?: 0L,
                quantity = entry.arguments?.getInt("qty") ?: 1,
                offerId = offerId,
                onBack = { nav.popBackStack() },
                onPaid = { orderId ->
                    // Pop EventDetail + Checkout so back from the result screen
                    // lands on Discover, never a consumed checkout.
                    nav.navigate("orderResult/$orderId") {
                        popUpTo("main")
                    }
                },
            )
        }
        composable(
            "orderResult/{orderId}",
            arguments = listOf(navArgument("orderId") { type = NavType.LongType }),
        ) { entry ->
            OrderResultScreen(
                orderId = entry.arguments?.getLong("orderId") ?: 0L,
                onViewTickets = {
                    selectedTab = 1
                    nav.popBackStack("main", inclusive = false)
                },
                onDone = { nav.popBackStack("main", inclusive = false) },
            )
        }
        composable(
            "ticket/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            TicketDetailScreen(
                ticketId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            "dashboard/{eventId}",
            arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
        ) { entry ->
            DashboardScreen(
                eventId = entry.arguments?.getLong("eventId") ?: 0L,
                onBack = { nav.popBackStack() },
                onEdit = { id -> nav.navigate("editEvent/$id") },
            )
        }
        composable(
            "editEvent/{eventId}",
            arguments = listOf(navArgument("eventId") { type = NavType.LongType }),
        ) { entry ->
            EditEventScreen(
                eventId = entry.arguments?.getLong("eventId") ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable("profile") {
            ProfileScreen(user = user, onBack = { nav.popBackStack() })
        }
        composable("notifications") {
            NotificationsScreen(
                onBack = { nav.popBackStack() },
                onOpenEvent = { id -> nav.navigate("event/$id") },
            )
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@Composable
private fun MainScaffold(
    user: User,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onEventClick: (Long) -> Unit,
    onOpenTicket: (Long) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val tabs = buildList {
        add(Tab("Discover", Icons.Outlined.Explore))
        add(Tab("Tickets", Icons.Outlined.ConfirmationNumber))
        if (user.isOrganizer) {
            add(Tab("Scan", Icons.Outlined.QrCodeScanner))
            add(Tab("Create", Icons.Outlined.AddCircleOutline))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { onTabSelected(index) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen(
                    user = user,
                    onEventClick = onEventClick,
                    onOpenNotifications = onOpenNotifications,
                    onOpenProfile = onOpenProfile,
                )
                1 -> TicketsScreen(onOpenTicket = onOpenTicket)
                2 -> ScannerScreen()
                3 -> CreateEventScreen(onCreated = { onTabSelected(0) })
            }
        }
    }
}
