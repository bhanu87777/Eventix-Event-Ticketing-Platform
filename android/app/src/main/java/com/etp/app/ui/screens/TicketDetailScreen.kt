package com.etp.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.etp.app.data.Repository
import com.etp.app.data.Ticket
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.Chip
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.BrandGradient
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.formatEventDateTime
import com.etp.app.util.qrBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class TicketDetailViewModel(private val repo: Repository) : ViewModel() {
    val ticket = MutableStateFlow<Ticket?>(null)
    val error = MutableStateFlow<String?>(null)

    fun load(id: Long) {
        viewModelScope.launch {
            // Cache-first via the wallet call — works fully offline.
            repo.myTickets()
                .onSuccess { result ->
                    val found = result.tickets.firstOrNull { it.id == id }
                    if (found != null) ticket.value = found else error.value = "Ticket not found"
                }
                .onFailure { error.value = it.message }
        }
    }
}

@Composable
fun TicketDetailScreen(ticketId: Long, onBack: () -> Unit) {
    val vm = etpViewModel { TicketDetailViewModel(it.repository) }
    val ticket by vm.ticket.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(ticketId) { vm.load(ticketId) }

    // Gate-ready: push screen brightness to max while this ticket is showing,
    // restore the previous value the moment we navigate away.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val previous = window?.attributes?.screenBrightness
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = 1f } }
        onDispose {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = previous ?: -1f } }
        }
    }

    when {
        error != null -> ErrorState(error!!, onRetry = { vm.load(ticketId) })
        ticket == null -> CenteredLoader()
        else -> TicketDetailContent(ticket!!, onBack)
    }
}

@Composable
private fun TicketDetailContent(ticket: Ticket, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(BrandGradient)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
        ) {
            Column(Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ticket.eventTitle, style = MaterialTheme.typography.headlineSmall)
                        if (ticket.tierName.isNotBlank()) {
                            Text(
                                ticket.tierName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
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

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("  ${formatEventDateTime(ticket.startsAt)}", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text("  ${ticket.venue}", style = MaterialTheme.typography.bodyLarge)
                }

                // Perforated-ticket divider.
                val outline = MaterialTheme.colorScheme.outline
                Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    drawLine(
                        color = outline,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)),
                    )
                }

                // QR codes must stay black-on-white for scanners, regardless of theme.
                val qr = remember(ticket.qr) { qrBitmap(ticket.qr) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "Ticket QR code",
                        modifier = Modifier.size(260.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    ticket.code.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Screen brightness is boosted for the scanner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}
