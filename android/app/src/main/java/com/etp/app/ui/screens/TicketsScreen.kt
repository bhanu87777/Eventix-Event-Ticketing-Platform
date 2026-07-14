package com.etp.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.etp.app.data.Repository
import com.etp.app.data.Ticket
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.Chip
import com.etp.app.ui.components.EmptyState
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.formatEventDateTime
import com.etp.app.util.qrBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TicketsViewModel(private val repo: Repository) : ViewModel() {
    val state = MutableStateFlow<ListState<Ticket>>(ListState.Loading)
    val refreshing = MutableStateFlow(false)

    fun load(asRefresh: Boolean = false) {
        viewModelScope.launch {
            if (asRefresh) refreshing.value = true else state.value = ListState.Loading
            repo.myTickets()
                .onSuccess { state.value = ListState.Ready(it) }
                .onFailure { state.value = ListState.Error(it.message ?: "Something went wrong") }
            refreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketsScreen() {
    val vm = etpViewModel { TicketsViewModel(it.repository) }
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()

    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize()) {
        Text(
            "My Tickets",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )
        when (val s = state) {
            is ListState.Loading -> CenteredLoader()
            is ListState.Error -> ErrorState(s.message, onRetry = { vm.load() })
            is ListState.Ready -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.load(asRefresh = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (s.items.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.ConfirmationNumber,
                        title = "No tickets yet",
                        subtitle = "Tickets you buy will appear here with their entry QR code.",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(s.items, key = { it.id }) { ticket -> TicketCard(ticket) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TicketCard(ticket: Ticket) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ticket.eventTitle, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${ticket.venue} · ${formatEventDateTime(ticket.startsAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (ticket.isCheckedIn) {
                    Chip(
                        text = "USED",
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Chip(text = "VALID", container = SuccessGreen.copy(alpha = 0.18f), content = SuccessGreen)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline)

            // QR codes must stay black-on-white for scanners, regardless of theme.
            val qr = remember(ticket.qr) { qrBitmap(ticket.qr) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "Ticket QR code",
                    modifier = Modifier.size(210.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                ticket.code.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
