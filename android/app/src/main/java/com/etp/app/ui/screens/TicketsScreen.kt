package com.etp.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.etp.app.ui.components.ConfirmDialog
import com.etp.app.ui.components.EmptyState
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.formatEventDateTime
import com.etp.app.util.qrBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date

class TicketsViewModel(private val repo: Repository) : ViewModel() {
    val state = MutableStateFlow<ListState<Ticket>>(ListState.Loading)
    val refreshing = MutableStateFlow(false)
    val fromCache = MutableStateFlow(false)
    val cachedAt = MutableStateFlow(0L)
    val cancelBusy = MutableStateFlow(false)
    val cancelError = MutableStateFlow<String?>(null)

    fun load(asRefresh: Boolean = false) {
        viewModelScope.launch {
            if (asRefresh) refreshing.value = true else state.value = ListState.Loading
            repo.myTickets()
                .onSuccess {
                    state.value = ListState.Ready(it.tickets)
                    fromCache.value = it.fromCache
                    cachedAt.value = it.fetchedAt
                }
                .onFailure { state.value = ListState.Error(it.message ?: "Something went wrong") }
            refreshing.value = false
        }
    }

    fun cancel(ticketId: Long, onDone: () -> Unit) {
        if (cancelBusy.value) return
        viewModelScope.launch {
            cancelBusy.value = true
            cancelError.value = null
            repo.cancelTicket(ticketId)
                .onSuccess {
                    load(asRefresh = true)
                    onDone()
                }
                .onFailure { cancelError.value = it.message }
            cancelBusy.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketsScreen(onOpenTicket: (Long) -> Unit) {
    val vm = etpViewModel { TicketsViewModel(it.repository) }
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val fromCache by vm.fromCache.collectAsState()
    val cachedAt by vm.cachedAt.collectAsState()
    val cancelBusy by vm.cancelBusy.collectAsState()
    val cancelError by vm.cancelError.collectAsState()
    var cancelling by remember { mutableStateOf<Ticket?>(null) }

    LaunchedEffect(Unit) { vm.load() }

    Column(Modifier.fillMaxSize()) {
        Text(
            "My Tickets",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
        )

        if (fromCache) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "  Offline — showing tickets saved ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(cachedAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

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
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(s.items, key = { it.id }) { ticket ->
                            TicketCard(
                                ticket = ticket,
                                onClick = { onOpenTicket(ticket.id) },
                                onCancel = { cancelling = ticket },
                            )
                        }
                    }
                }
            }
        }
    }

    cancelling?.let { ticket ->
        ConfirmDialog(
            title = "Cancel this ticket?",
            body = "Your seat for “${ticket.eventTitle}” is released — if there's a waitlist, it goes to the next person. This can't be undone." +
                (cancelError?.let { "\n\n$it" } ?: ""),
            confirmLabel = "Cancel ticket",
            destructive = true,
            busy = cancelBusy,
            onConfirm = { vm.cancel(ticket.id) { cancelling = null } },
            onDismiss = { cancelling = null },
        )
    }
}

private fun isUpcoming(iso: String): Boolean =
    runCatching { Instant.parse(iso).isAfter(Instant.now()) }.getOrDefault(false)

@Composable
private fun TicketCard(ticket: Ticket, onClick: () -> Unit, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // QR thumbnail: the full scannable code lives on the detail screen.
                val qr = remember(ticket.qr) { qrBitmap(ticket.qr) }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(6.dp),
                ) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "Ticket QR code",
                        modifier = Modifier.size(64.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(ticket.eventTitle, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${ticket.venue} · ${formatEventDateTime(ticket.startsAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    if (ticket.tierName.isNotBlank()) {
                        Text(
                            ticket.tierName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                when (ticket.status) {
                    "valid" -> Chip(text = "VALID", container = SuccessGreen.copy(alpha = 0.18f), content = SuccessGreen)
                    "checked_in" -> Chip(text = "USED")
                    "cancelled" -> Chip(text = "CANCELLED", container = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), content = MaterialTheme.colorScheme.error)
                    "void" -> Chip(text = "EVENT OFF", container = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), content = MaterialTheme.colorScheme.error)
                }
            }
            if (ticket.isValid && isUpcoming(ticket.startsAt)) {
                TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel ticket", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
