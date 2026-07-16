package com.etp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.etp.app.data.AppNotification
import com.etp.app.data.Repository
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.EmptyState
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.etpViewModel
import com.etp.app.util.formatEventDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel(private val repo: Repository) : ViewModel() {
    val state = MutableStateFlow<ListState<AppNotification>>(ListState.Loading)

    fun load() {
        viewModelScope.launch {
            repo.notifications()
                .onSuccess {
                    state.value = ListState.Ready(it.notifications)
                    if (it.unreadCount > 0) repo.markAllRead() // opening the screen reads them
                }
                .onFailure { state.value = ListState.Error(it.message ?: "Something went wrong") }
        }
    }
}

@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpenEvent: (Long) -> Unit) {
    val vm = etpViewModel { NotificationsViewModel(it.repository) }
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Notifications", style = MaterialTheme.typography.headlineSmall)
        }

        when (val s = state) {
            is ListState.Loading -> CenteredLoader()
            is ListState.Error -> ErrorState(s.message, onRetry = { vm.load() })
            is ListState.Ready -> {
                if (s.items.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.NotificationsNone,
                        title = "All caught up",
                        subtitle = "Purchase confirmations, event changes, and waitlist offers land here.",
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(s.items, key = { it.id }) { n ->
                            NotificationRow(n, onOpenEvent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: AppNotification, onOpenEvent: (Long) -> Unit) {
    Surface(
        color = if (n.read) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = n.eventId != null) { n.eventId?.let(onOpenEvent) },
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            if (!n.read) {
                Box(
                    Modifier
                        .padding(top = 7.dp)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Spacer(Modifier.size(10.dp))
            } else {
                Spacer(Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(n.title, style = MaterialTheme.typography.titleSmall)
                if (n.body.isNotBlank()) {
                    Text(
                        n.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    formatEventDateTime(n.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
