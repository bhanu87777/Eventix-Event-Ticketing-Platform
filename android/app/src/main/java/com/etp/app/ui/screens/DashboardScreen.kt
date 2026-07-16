package com.etp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.etp.app.data.Attendee
import com.etp.app.data.EventStats
import com.etp.app.data.Repository
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.Chip
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.SalesBarChart
import com.etp.app.ui.components.StatTile
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.formatMoney
import com.etp.app.util.shareCsv
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class DashboardViewModel(private val repo: Repository) : ViewModel() {
    val stats = MutableStateFlow<EventStats?>(null)
    val attendees = MutableStateFlow<List<Attendee>>(emptyList())
    val total = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    val search = MutableStateFlow("")
    val csvError = MutableStateFlow<String?>(null)

    private var eventId = 0L

    @OptIn(FlowPreview::class)
    fun start(eventId: Long) {
        if (this.eventId != 0L) return
        this.eventId = eventId
        viewModelScope.launch {
            repo.eventStats(eventId)
                .onSuccess { stats.value = it }
                .onFailure { error.value = it.message }
        }
        viewModelScope.launch {
            search.debounce(300).collectLatest { q ->
                repo.attendees(eventId, q).onSuccess {
                    attendees.value = it.attendees
                    total.value = it.total
                }
            }
        }
    }

    fun exportCsv(onReady: (String) -> Unit) {
        viewModelScope.launch {
            repo.attendeesCsv(eventId)
                .onSuccess(onReady)
                .onFailure { csvError.value = it.message }
        }
    }
}

@Composable
fun DashboardScreen(eventId: Long, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val vm = etpViewModel { DashboardViewModel(it.repository) }
    val stats by vm.stats.collectAsState()
    val attendees by vm.attendees.collectAsState()
    val total by vm.total.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(eventId) { vm.start(eventId) }
    LaunchedEffect(search) { vm.search.value = search }

    when {
        error != null -> ErrorState(error!!, onRetry = null)
        stats == null -> CenteredLoader()
        else -> {
            val s = stats!!
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(listOf("header")) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text("Dashboard", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onEdit(eventId) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit event")
                        }
                        IconButton(onClick = {
                            vm.exportCsv { csv -> shareCsv(context, "attendees-$eventId.csv", csv) }
                        }) {
                            Icon(Icons.Outlined.IosShare, contentDescription = "Export attendees CSV")
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatTile("Sold", "${s.sold}", Modifier.weight(1f))
                        StatTile("Revenue", formatMoney(s.revenueCents, "INR"), Modifier.weight(1.3f))
                        StatTile("Checked in", "${s.checkinRate}%", Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Sales by day",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    SalesBarChart(s.salesByDay, Modifier.padding(horizontal = 20.dp, vertical = 10.dp))

                    if (s.byTier.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tiers",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        s.byTier.forEach { tier ->
                            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    Text("${tier.name} · ${formatMoney(tier.priceCents, "INR")}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text("${tier.sold}/${tier.capacity}", style = MaterialTheme.typography.bodyMedium)
                                }
                                LinearProgressIndicator(
                                    progress = { if (tier.capacity > 0) tier.sold.toFloat() / tier.capacity else 0f },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp).clip(RoundedCornerShape(50)),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Attendees ($total)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("Search name, email, or code") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }

                items(attendees, key = { it.ticketId }) { a ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(a.userName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                a.userEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Chip(text = a.tierName)
                        Spacer(Modifier.size(6.dp))
                        when (a.status) {
                            "checked_in" -> Icon(Icons.Filled.CheckCircle, contentDescription = "Checked in", tint = SuccessGreen, modifier = Modifier.size(20.dp))
                            "cancelled", "void" -> Chip(text = a.status.uppercase(), container = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), content = MaterialTheme.colorScheme.error)
                            else -> Chip(text = "VALID", container = SuccessGreen.copy(alpha = 0.18f), content = SuccessGreen)
                        }
                    }
                }
            }
        }
    }
}
