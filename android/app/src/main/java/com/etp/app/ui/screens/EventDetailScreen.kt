package com.etp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.etp.app.data.TicketTier
import com.etp.app.data.User
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.Chip
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.QuantityStepper
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.addToCalendar
import com.etp.app.util.formatEventDateTime
import com.etp.app.util.formatMoney
import com.etp.app.util.shareEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class EventDetailViewModel(private val repo: Repository) : ViewModel() {
    val event = MutableStateFlow<Event?>(null)
    val error = MutableStateFlow<String?>(null)
    val actionError = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun load(id: Long) {
        viewModelScope.launch {
            error.value = null
            repo.event(id)
                .onSuccess { event.value = it }
                .onFailure { error.value = it.message }
        }
    }

    fun toggleFavorite() {
        val e = event.value ?: return
        event.value = e.copy(isFavorite = !e.isFavorite)
        viewModelScope.launch {
            repo.setFavorite(e.id, !e.isFavorite).onFailure { event.value = e }
        }
    }

    fun joinWaitlist() {
        val e = event.value ?: return
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            actionError.value = null
            repo.joinWaitlist(e.id)
                .onSuccess { load(e.id) }
                .onFailure { actionError.value = it.message }
            busy.value = false
        }
    }

    fun leaveWaitlist() {
        val e = event.value ?: return
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            actionError.value = null
            repo.leaveWaitlist(e.id)
                .onSuccess { load(e.id) }
                .onFailure { actionError.value = it.message }
            busy.value = false
        }
    }
}

@Composable
fun EventDetailScreen(
    eventId: Long,
    user: User,
    onBack: () -> Unit,
    onCheckout: (tierId: Long, quantity: Int, offerId: Long?) -> Unit,
    onManage: (Long) -> Unit,
) {
    val vm = etpViewModel { EventDetailViewModel(it.repository) }
    val event by vm.event.collectAsState()
    val error by vm.error.collectAsState()
    val actionError by vm.actionError.collectAsState()
    val busy by vm.busy.collectAsState()

    LaunchedEffect(eventId) { vm.load(eventId) }

    when {
        error != null -> ErrorState(error!!, onRetry = { vm.load(eventId) })
        event == null -> CenteredLoader()
        else -> EventDetailContent(
            event = event!!,
            user = user,
            busy = busy,
            actionError = actionError,
            onBack = onBack,
            onCheckout = onCheckout,
            onManage = onManage,
            onToggleFavorite = { vm.toggleFavorite() },
            onJoinWaitlist = { vm.joinWaitlist() },
            onLeaveWaitlist = { vm.leaveWaitlist() },
        )
    }
}

@Composable
private fun EventDetailContent(
    event: Event,
    user: User,
    busy: Boolean,
    actionError: String?,
    onBack: () -> Unit,
    onCheckout: (Long, Int, Long?) -> Unit,
    onManage: (Long) -> Unit,
    onToggleFavorite: () -> Unit,
    onJoinWaitlist: () -> Unit,
    onLeaveWaitlist: () -> Unit,
) {
    val context = LocalContext.current
    val tiers = event.sellableTiers
    var selectedTierId by rememberSaveable(event.id) {
        mutableStateOf(tiers.firstOrNull { it.seatsLeft > 0 }?.id ?: tiers.first().id)
    }
    val selectedTier = tiers.firstOrNull { it.id == selectedTierId } ?: tiers.first()
    var quantity by rememberSaveable(event.id) { mutableIntStateOf(1) }
    val maxQty = minOf(10, maxOf(1, selectedTier.seatsLeft))
    if (quantity > maxQty) quantity = maxQty

    val soldOut = tiers.all { it.seatsLeft <= 0 }
    val onWaitlist = event.myWaitlist?.status == "waiting"
    val offer = event.myWaitlist?.takeIf { it.status == "offered" }
    val isOwner = user.isOrganizer && user.id == event.organizerId

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
                Row(
                    Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Row {
                        IconButton(
                            onClick = { addToCalendar(context, event) },
                            modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(Icons.Outlined.CalendarMonth, contentDescription = "Add to calendar", tint = Color.White)
                        }
                        Spacer(Modifier.size(8.dp))
                        IconButton(
                            onClick = { shareEvent(context, event) },
                            modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White)
                        }
                        Spacer(Modifier.size(8.dp))
                        IconButton(
                            onClick = onToggleFavorite,
                            modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                        ) {
                            Icon(
                                if (event.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (event.isFavorite) MaterialTheme.colorScheme.tertiary else Color.White,
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                if (event.isCancelled) {
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Text(
                            "This event has been cancelled by the organizer.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }

                offer?.let {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                "Your spot opened up!",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                "A seat is being held for you until ${formatEventDateTime(it.offerExpiresAt ?: "")}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Button(
                                onClick = { onCheckout(it.tierId ?: selectedTier.id, 1, it.id) },
                                modifier = Modifier.padding(top = 10.dp),
                            ) { Text("Claim my seat") }
                        }
                    }
                }

                Text(event.title, style = MaterialTheme.typography.headlineMedium)
                event.categoryName?.let {
                    Spacer(Modifier.height(8.dp))
                    Chip(text = it)
                }
                Spacer(Modifier.height(16.dp))

                DetailRow(icon = { Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary) }, text = formatEventDateTime(event.startsAt))
                DetailRow(icon = { Icon(Icons.Outlined.Place, null, tint = MaterialTheme.colorScheme.primary) }, text = event.venue)
                DetailRow(icon = { Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.primary) }, text = "Hosted by ${event.organizerName}")

                if (isOwner) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { onManage(event.id) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Insights, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Manage event", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Spacer(Modifier.height(20.dp))
                SeatAvailability(event)

                if (!event.isCancelled && !soldOut) {
                    Spacer(Modifier.height(20.dp))
                    Text("Choose a tier", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    tiers.forEach { tier ->
                        TierCard(
                            tier = tier,
                            currency = event.currency,
                            selected = tier.id == selectedTier.id,
                            onSelect = { if (tier.seatsLeft > 0) selectedTierId = tier.id },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Tickets", style = MaterialTheme.typography.titleMedium)
                        QuantityStepper(value = quantity, onChange = { quantity = it }, max = maxQty)
                    }
                }

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
                actionError?.let {
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
                            formatMoney(selectedTier.priceCents * quantity, event.currency),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            if (quantity == 1) "1 ticket · ${selectedTier.name}" else "$quantity tickets · ${selectedTier.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        event.isCancelled -> Button(onClick = {}, enabled = false, shape = RoundedCornerShape(16.dp), modifier = Modifier.height(52.dp)) {
                            Text("Cancelled")
                        }
                        soldOut -> Button(
                            onClick = { if (onWaitlist) onLeaveWaitlist() else onJoinWaitlist() },
                            enabled = !busy,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(52.dp),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            } else {
                                Text(if (onWaitlist) "Leave waitlist" else "Join waitlist")
                            }
                        }
                        else -> Button(
                            onClick = { onCheckout(selectedTier.id, quantity, null) },
                            enabled = selectedTier.seatsLeft > 0,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.height(52.dp),
                        ) {
                            Text("Continue")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TierCard(tier: TicketTier, currency: String, selected: Boolean, onSelect: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = shape,
            )
            .clickable(onClick = onSelect),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tier.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (tier.seatsLeft > 0) "${tier.seatsLeft} left" else "Sold out",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tier.seatsLeft > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Text(
                formatMoney(tier.priceCents, currency),
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
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
