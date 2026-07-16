package com.etp.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.etp.app.data.Category
import com.etp.app.data.Event
import com.etp.app.data.Repository
import com.etp.app.data.User
import com.etp.app.ui.components.AppHeader
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.Chip
import com.etp.app.ui.components.EmptyState
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.formatEventDateTime
import com.etp.app.util.formatMoney
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

sealed interface ListState<out T> {
    data object Loading : ListState<Nothing>
    data class Error(val message: String) : ListState<Nothing>
    data class Ready<T>(val items: List<T>) : ListState<T>
}

private const val PAGE_SIZE = 20

val SORT_OPTIONS = listOf(
    "date_asc" to "Date · soonest first",
    "date_desc" to "Date · latest first",
    "price_asc" to "Price · low to high",
    "price_desc" to "Price · high to low",
)

class HomeViewModel(private val repo: Repository) : ViewModel() {
    val state = MutableStateFlow<ListState<Event>>(ListState.Loading)
    val refreshing = MutableStateFlow(false)
    val loadingMore = MutableStateFlow(false)
    val hasMore = MutableStateFlow(false)
    val categories = MutableStateFlow<List<Category>>(emptyList())
    val unread = MutableStateFlow(0)

    private var offset = 0
    private var lastQuery: String? = null
    private var lastCategory: Long? = null
    private var lastSort: String = "date_asc"
    private var lastFavorites = false

    fun load(
        query: String? = null,
        categoryId: Long? = null,
        sort: String = "date_asc",
        favoritesOnly: Boolean = false,
        asRefresh: Boolean = false,
    ) {
        lastQuery = query
        lastCategory = categoryId
        lastSort = sort
        lastFavorites = favoritesOnly
        offset = 0
        viewModelScope.launch {
            if (asRefresh) refreshing.value = true else state.value = ListState.Loading
            repo.events(query, categoryId, sort, if (favoritesOnly) true else null, limit = PAGE_SIZE, offset = 0)
                .onSuccess {
                    state.value = ListState.Ready(it.events)
                    hasMore.value = it.hasMore
                    offset = it.events.size
                }
                .onFailure { state.value = ListState.Error(it.message ?: "Something went wrong") }
            refreshing.value = false
        }
        refreshBadge()
    }

    fun loadMore() {
        val current = (state.value as? ListState.Ready)?.items ?: return
        if (loadingMore.value || !hasMore.value) return
        loadingMore.value = true
        viewModelScope.launch {
            repo.events(lastQuery, lastCategory, lastSort, if (lastFavorites) true else null, limit = PAGE_SIZE, offset = offset)
                .onSuccess {
                    state.value = ListState.Ready(current + it.events)
                    hasMore.value = it.hasMore
                    offset += it.events.size
                }
            loadingMore.value = false
        }
    }

    fun loadCategories() {
        if (categories.value.isNotEmpty()) return
        viewModelScope.launch {
            repo.categories().onSuccess { categories.value = it }
        }
    }

    fun refreshBadge() {
        viewModelScope.launch { repo.unreadCount().onSuccess { unread.value = it } }
    }

    /** Optimistic heart toggle: flip locally, revert if the call fails. */
    fun toggleFavorite(event: Event) {
        val current = (state.value as? ListState.Ready)?.items ?: return
        fun apply(fav: Boolean) {
            val items = (state.value as? ListState.Ready)?.items ?: return
            state.value = ListState.Ready(items.map { if (it.id == event.id) it.copy(isFavorite = fav) else it })
        }
        apply(!event.isFavorite)
        viewModelScope.launch {
            repo.setFavorite(event.id, !event.isFavorite).onFailure { apply(event.isFavorite) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    user: User,
    onEventClick: (Long) -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val vm = etpViewModel { HomeViewModel(it.repository) }
    val state by vm.state.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val loadingMore by vm.loadingMore.collectAsState()
    val categories by vm.categories.collectAsState()
    val unread by vm.unread.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sort by rememberSaveable { mutableStateOf("date_asc") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadCategories() }
    LaunchedEffect(query, categoryId, sort, favoritesOnly) {
        vm.load(query, categoryId, sort, favoritesOnly, asRefresh = query.isNotBlank())
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 5 && info.totalItemsCount > 0
        }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) vm.loadMore() }
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = "Discover",
            userName = user.name,
            unreadCount = unread,
            onBell = onOpenNotifications,
            onAvatar = onOpenProfile,
            modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search events or venues") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.Outlined.SwapVert, contentDescription = "Sort")
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SORT_OPTIONS.forEach { (key, label) ->
                        DropdownMenuItem(
                            text = { Text(if (key == sort) "✓ $label" else label) },
                            onClick = {
                                sort = key
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            FilterChip(
                selected = favoritesOnly,
                onClick = { favoritesOnly = !favoritesOnly },
                label = { Text("Favorites") },
                leadingIcon = {
                    Icon(
                        if (favoritesOnly) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            FilterChip(
                selected = categoryId == null,
                onClick = { categoryId = null },
                label = { Text("All") },
            )
            categories.forEach { cat ->
                FilterChip(
                    selected = categoryId == cat.id,
                    onClick = { categoryId = if (categoryId == cat.id) null else cat.id },
                    label = { Text(cat.name) },
                )
            }
        }

        when (val s = state) {
            is ListState.Loading -> CenteredLoader()
            is ListState.Error -> ErrorState(s.message, onRetry = { vm.load(query, categoryId, sort, favoritesOnly) })
            is ListState.Ready -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    vm.load(query, categoryId, sort, favoritesOnly, asRefresh = true)
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (s.items.isEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.EventBusy,
                        title = if (favoritesOnly) "No favorites yet" else "No events found",
                        subtitle = when {
                            favoritesOnly -> "Tap the heart on any event to save it here."
                            query.isBlank() -> "Check back soon — new events are added all the time."
                            else -> "Try a different search."
                        },
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(s.items, key = { it.id }) { event ->
                            EventCard(
                                event = event,
                                onClick = { onEventClick(event.id) },
                                onToggleFavorite = { vm.toggleFavorite(event) },
                            )
                        }
                        if (loadingMore) {
                            items(listOf("loader")) {
                                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: Event, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
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

        // Heart: top-left so it never collides with the price chip.
        IconButton(onClick = onToggleFavorite, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
            Icon(
                if (event.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (event.isFavorite) "Remove from favorites" else "Add to favorites",
                tint = if (event.isFavorite) MaterialTheme.colorScheme.tertiary else Color.White,
            )
        }

        Row(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (event.isCancelled) {
                Chip(text = "CANCELLED", container = MaterialTheme.colorScheme.error, content = Color.White)
            }
            Chip(
                text = formatMoney(event.minPriceCents, event.currency),
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                event.categoryName?.let {
                    Chip(text = it, container = Color.Black.copy(alpha = 0.55f), content = Color.White)
                }
            }
        }
    }
}
