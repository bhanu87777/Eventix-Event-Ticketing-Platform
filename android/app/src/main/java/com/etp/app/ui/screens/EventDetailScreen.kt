package com.etp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.etp.app.data.Event
import com.etp.app.data.Repository
import com.etp.app.data.Ticket
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.formatEventDateTime
import com.etp.app.util.formatMoney
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class EventDetailViewModel(private val repo: Repository) : ViewModel() {
    val event = MutableStateFlow<Event?>(null)
    val error = MutableStateFlow<String?>(null)
    val buying = MutableStateFlow(false)
    val purchaseError = MutableStateFlow<String?>(null)
    val purchased = MutableStateFlow<Ticket?>(null)

    /** One key per purchase *intent*: a retry after a network failure reuses
     *  it, so the server can never sell us two tickets for one tap. */
    private var idempotencyKey = UUID.randomUUID().toString()

    fun load(id: Long) {
        viewModelScope.launch {
            error.value = null
            repo.event(id)
                .onSuccess { event.value = it }
                .onFailure { error.value = it.message }
        }
    }

    fun buy(id: Long) {
        if (buying.value) return
        viewModelScope.launch {
            buying.value = true
            purchaseError.value = null
            repo.purchase(id, idempotencyKey)
                .onSuccess {
                    purchased.value = it
                    idempotencyKey = UUID.randomUUID().toString()
                    load(id)
                }
                .onFailure { purchaseError.value = it.message }
            buying.value = false
        }
    }

    fun dismissPurchase() {
        purchased.value = null
    }
}

@Composable
fun EventDetailScreen(eventId: Long, onBack: () -> Unit, onViewTickets: () -> Unit) {
    val vm = etpViewModel { EventDetailViewModel(it.repository) }
    val event by vm.event.collectAsState()
    val error by vm.error.collectAsState()
    val buying by vm.buying.collectAsState()
    val purchaseError by vm.purchaseError.collectAsState()
    val purchased by vm.purchased.collectAsState()

    LaunchedEffect(eventId) { vm.load(eventId) }

    when {
        error != null -> ErrorState(error!!, onRetry = { vm.load(eventId) })
        event == null -> CenteredLoader()
        else -> EventDetailContent(
            event = event!!,
            buying = buying,
            purchaseError = purchaseError,
            onBack = onBack,
            onBuy = { vm.buy(eventId) },
        )
    }

    purchased?.let { ticket ->
        AlertDialog(
            onDismissRequest = { vm.dismissPurchase() },
            icon = {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(48.dp))
            },
            title = { Text("You're going! 🎉") },
            text = { Text("Your ticket for “${ticket.eventTitle}” is in your wallet. Show its QR code at the gate.") },
            confirmButton = {
                Button(onClick = { vm.dismissPurchase(); onViewTickets() }) { Text("View ticket") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPurchase() }) { Text("Keep browsing") }
            },
        )
    }
}

@Composable
private fun EventDetailContent(
    event: Event,
    buying: Boolean,
    purchaseError: String?,
    onBack: () -> Unit,
    onBuy: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(event.imageUrl.ifBlank { null })
                        .crossfade(true)
                        .build(),
                    contentDescription = event.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                                startY = 500f,
                            ),
                        ),
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(event.title, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(16.dp))

                DetailRow(icon = { Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary) }, text = formatEventDateTime(event.startsAt))
                DetailRow(icon = { Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.primary) }, text = event.venue)
                DetailRow(icon = { Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.primary) }, text = "Hosted by ${event.organizerName}")

                Spacer(Modifier.height(20.dp))
                SeatAvailability(event)

                if (event.description.isNotBlank()) {
                    Spacer(Modifier.height(20.dp))
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Text(
                        event.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
            Column(Modifier.navigationBarsPadding().padding(20.dp)) {
                purchaseError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            formatMoney(event.priceCents, event.currency),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "per ticket",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = onBuy,
                        enabled = !buying && event.seatsLeft > 0,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.height(52.dp),
                    ) {
                        when {
                            buying -> CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            event.seatsLeft <= 0 -> Text("Sold out")
                            else -> Text("Buy ticket")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(icon: @Composable () -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        icon()
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SeatAvailability(event: Event) {
    val fraction = if (event.capacity > 0) event.ticketsSold.toFloat() / event.capacity else 0f
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Availability", style = MaterialTheme.typography.titleMedium)
            Text(
                if (event.seatsLeft > 0) "${event.seatsLeft} of ${event.capacity} left" else "Sold out",
                style = MaterialTheme.typography.bodyMedium,
                color = if (event.seatsLeft > 0) SuccessGreen else MaterialTheme.colorScheme.error,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(50)),
        )
    }
}
