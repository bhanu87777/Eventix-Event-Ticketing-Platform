package com.etp.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.etp.app.data.Category
import com.etp.app.data.Event
import com.etp.app.data.PatchEventRequest
import com.etp.app.data.Repository
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.ConfirmDialog
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.etpViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class EditEventViewModel(private val repo: Repository) : ViewModel() {
    val event = MutableStateFlow<Event?>(null)
    val categories = MutableStateFlow<List<Category>>(emptyList())
    val error = MutableStateFlow<String?>(null)
    val submitting = MutableStateFlow(false)
    val submitError = MutableStateFlow<String?>(null)
    val saved = MutableStateFlow(false)
    val cancelBusy = MutableStateFlow(false)
    val cancelled = MutableStateFlow(false)

    fun load(id: Long) {
        viewModelScope.launch {
            repo.event(id)
                .onSuccess { event.value = it }
                .onFailure { error.value = it.message }
            repo.categories().onSuccess { categories.value = it }
        }
    }

    fun save(id: Long, body: PatchEventRequest) {
        if (submitting.value) return
        viewModelScope.launch {
            submitting.value = true
            submitError.value = null
            repo.updateEvent(id, body)
                .onSuccess { saved.value = true }
                .onFailure { submitError.value = it.message }
            submitting.value = false
        }
    }

    fun cancelEvent(id: Long) {
        if (cancelBusy.value) return
        viewModelScope.launch {
            cancelBusy.value = true
            repo.cancelEvent(id)
                .onSuccess { cancelled.value = true }
                .onFailure { submitError.value = it.message }
            cancelBusy.value = false
        }
    }
}

@Composable
fun EditEventScreen(eventId: Long, onBack: () -> Unit) {
    val vm = etpViewModel { EditEventViewModel(it.repository) }
    val event by vm.event.collectAsState()
    val categories by vm.categories.collectAsState()
    val error by vm.error.collectAsState()
    val submitting by vm.submitting.collectAsState()
    val submitError by vm.submitError.collectAsState()
    val saved by vm.saved.collectAsState()
    val cancelBusy by vm.cancelBusy.collectAsState()
    val cancelled by vm.cancelled.collectAsState()
    var confirmCancel by remember { mutableStateOf(false) }

    LaunchedEffect(eventId) { vm.load(eventId) }
    LaunchedEffect(saved, cancelled) { if (saved || cancelled) onBack() }

    when {
        error != null -> ErrorState(error!!, onRetry = { vm.load(eventId) })
        event == null -> CenteredLoader()
        else -> {
            val form = remember(event) { EventFormState(event) }
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text("Edit event", style = MaterialTheme.typography.headlineSmall)
                }

                EventFormFields(form = form, categories = categories)
                Text(
                    "Attendees are notified automatically when the venue or time changes. " +
                        "Ticket tiers can't be edited after publishing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )

                submitError?.let {
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
                        val problem = form.validate()
                        if (problem != null) {
                            vm.submitError.value = problem
                        } else {
                            vm.save(eventId, form.toPatch())
                        }
                    },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    } else {
                        Text("Save changes")
                    }
                }
                TextButton(
                    onClick = { confirmCancel = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
                ) {
                    Text("Cancel event", color = MaterialTheme.colorScheme.error)
                }
            }

            if (confirmCancel) {
                ConfirmDialog(
                    title = "Cancel this event?",
                    body = "All valid tickets are voided and every ticket holder is notified. This can't be undone.",
                    confirmLabel = "Cancel event",
                    destructive = true,
                    busy = cancelBusy,
                    onConfirm = { vm.cancelEvent(eventId) },
                    onDismiss = { confirmCancel = false },
                )
            }
        }
    }
}
