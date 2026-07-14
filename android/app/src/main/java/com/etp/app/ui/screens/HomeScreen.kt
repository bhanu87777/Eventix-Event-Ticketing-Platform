package com.etp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.etp.app.data.Event
import com.etp.app.data.Repository
import com.etp.app.ui.components.Chip
import com.etp.app.ui.components.EmptyState
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.etpViewModel
import com.etp.app.data.User
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.formatEventDateTime
import com.etp.app.util.formatMoney
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface ListState<out T> {
    data object Loading : ListState<Nothing>
    data class Error(val message: String) : ListState<Nothing>
    data class Ready<T>(val items: List<T>) : ListState<T>
}

class HomeViewModel(private val repo: Repository) : ViewModel() {
    val state = MutableStateFlow<ListState<Event>>(ListState.Loading)
    val refreshing = MutableStateFlow(false)

    fun load(query: String? = null, asRefresh: Boolean = false) {
        viewModelScope.launch {
            if (asRefresh) refreshing.value = true else state.value = ListState.Loading
            repo.events(query)
                .onSuccess { state.value = ListState.Ready(it) }
                .onFailure { state.value = ListState.Error(it.message ?: "Something went wrong") }
            refreshing.value = false
        }
    }

    fun logout() {
        viewModelScope.launch { repo.session.logout() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(user: User, onEventClick: (Long) -> Unit) {
    val vm = etpViewModel { HomeViewModel(it.repository) }
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.load(query) }
    LaunchedEffect(query) { vm.load(query, asRefresh = true) }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Hi ${user.name.substringBefore(' ')} 👋",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Find your next event", style = MaterialTheme.typography.headlineSmall)
            }
            IconButton(onClick = { vm.logout() }) {
                Icon(
                    Icons.AutoMirrored.Outlined.Logout,
                    contentDescription = "Log out",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search events or venues") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )

        when (val s = state) {
            is ListState.Loading -> CenteredLoader()
            is ListState.Error -> ErrorState(s.message, onRetry = { vm.load(query) })
            is ListState.Ready -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.load(query, asRefresh = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (s.items.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.EventBusy,
                        title = "No events found",
                        subtitle = if (query.isBlank()) "Check back soon — new events are added all the time." else "Try a different search.",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(s.items, key = { it.id }) { event ->
                            EventCard(event = event, onClick = { onEventClick(event.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: Event, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
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
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        startY = 200f,
                    ),
                ),
        )

        Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            Chip(
                text = formatMoney(event.priceCents, event.currency),
                container = Color.Black.copy(alpha = 0.55f),
                content = Color.White,
            )
        }

        Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(
                event.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Place,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    " ${event.venue} · ${formatEventDateTime(event.startsAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            val low = event.seatsLeft in 1..20
            Chip(
                text = when {
                    event.seatsLeft <= 0 -> "Sold out"
                    low -> "Only ${event.seatsLeft} left"
                    else -> "${event.seatsLeft} seats left"
                },
                container = when {
                    event.seatsLeft <= 0 -> MaterialTheme.colorScheme.error.copy(alpha = 0.9f)
                    low -> Color(0xFFF59E0B).copy(alpha = 0.9f)
                    else -> SuccessGreen.copy(alpha = 0.9f)
                },
                content = Color.Black.copy(alpha = 0.85f),
            )
        }
    }
}
