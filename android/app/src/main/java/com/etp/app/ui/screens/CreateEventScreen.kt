package com.etp.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.etp.app.data.Category
import com.etp.app.data.CreateEventRequest
import com.etp.app.data.Repository
import com.etp.app.ui.components.etpViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class CreateEventViewModel(private val repo: Repository) : ViewModel() {
    val submitting = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val createdTitle = MutableStateFlow<String?>(null)
    val categories = MutableStateFlow<List<Category>>(emptyList())

    fun loadCategories() {
        if (categories.value.isNotEmpty()) return
        viewModelScope.launch { repo.categories().onSuccess { categories.value = it } }
    }

    fun create(body: CreateEventRequest) {
        if (submitting.value) return
        viewModelScope.launch {
            submitting.value = true
            error.value = null
            repo.createEvent(body)
                .onSuccess { createdTitle.value = it.title }
                .onFailure { error.value = it.message }
            submitting.value = false
        }
    }
}

@Composable
fun CreateEventScreen(onCreated: () -> Unit) {
    val vm = etpViewModel { CreateEventViewModel(it.repository) }
    val submitting by vm.submitting.collectAsState()
    val error by vm.error.collectAsState()
    val createdTitle by vm.createdTitle.collectAsState()
    val categories by vm.categories.collectAsState()

    val form = remember { EventFormState() }
    val tiers = remember { mutableStateListOf(TierRowState("General", "499", "100")) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.loadCategories() }
    LaunchedEffect(createdTitle) { if (createdTitle != null) onCreated() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Text(
            "Create Event",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp, bottom = 16.dp),
        )

        EventFormFields(form = form, categories = categories)

        Spacer(Modifier.height(20.dp))
        TierEditor(
            tiers = tiers,
            onAdd = { tiers.add(TierRowState()) },
            onRemove = { tiers.removeAt(it) },
        )

        (localError ?: error)?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                localError = null
                val (request, problem) = buildCreateRequest(form, tiers)
                if (request == null) {
                    localError = problem
                } else {
                    vm.create(request)
                }
            },
            enabled = !submitting,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (submitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Publish event")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
