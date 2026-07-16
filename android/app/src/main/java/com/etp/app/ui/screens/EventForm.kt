package com.etp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.etp.app.data.Category
import com.etp.app.data.CreateEventRequest
import com.etp.app.data.Event
import com.etp.app.data.PatchEventRequest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Shared Create/Edit form state. Values live in plain compose state; Create
 * and Edit both consume [EventFormFields] so the two screens can't drift.
 */
class EventFormState(prefill: Event? = null) {
    var title by mutableStateOf(prefill?.title ?: "")
    var description by mutableStateOf(prefill?.description ?: "")
    var venue by mutableStateOf(prefill?.venue ?: "")
    var imageUrl by mutableStateOf(prefill?.imageUrl ?: "")
    var categoryId by mutableStateOf(prefill?.categoryId)
    var time by mutableStateOf(
        prefill?.let {
            runCatching {
                DateTimeFormatter.ofPattern("HH:mm")
                    .format(Instant.parse(it.startsAt).atZone(ZoneId.systemDefault()))
            }.getOrDefault("18:30")
        } ?: "18:30",
    )
    var dateMillis by mutableStateOf(
        prefill?.let {
            runCatching { Instant.parse(it.startsAt).toEpochMilli() }.getOrNull()
        },
    )

    fun startsAtIso(): String? {
        val date = dateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: return null
        val parsedTime = runCatching { LocalTime.parse(time) }.getOrNull() ?: return null
        return date.atTime(parsedTime).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }

    /** Basic validation shared by Create/Edit; returns an error or null. */
    fun validate(): String? = when {
        title.isBlank() || venue.isBlank() -> "Title and venue are required"
        dateMillis == null -> "Pick a date"
        runCatching { LocalTime.parse(time) }.getOrNull() == null -> "Time must look like 18:30"
        categoryId == null -> "Pick a category"
        else -> null
    }

    fun toPatch(): PatchEventRequest = PatchEventRequest(
        title = title.trim(),
        description = description.trim(),
        venue = venue.trim(),
        startsAt = startsAtIso(),
        imageUrl = imageUrl.trim(),
        categoryId = categoryId,
    )
}

/** One editable ticket tier row (Create only — Edit manages tiers server-side). */
class TierRowState(name: String = "", price: String = "", capacity: String = "") {
    var name by mutableStateOf(name)
    var priceRupees by mutableStateOf(price)
    var capacity by mutableStateOf(capacity)
}

fun buildCreateRequest(form: EventFormState, tiers: List<TierRowState>): Pair<CreateEventRequest?, String?> {
    form.validate()?.let { return null to it }
    val startsAt = form.startsAtIso() ?: return null to "Pick a date"
    val parsed = tiers.mapIndexed { i, t ->
        val cap = t.capacity.toIntOrNull()
        if (t.name.isBlank()) return null to "Tier ${i + 1} needs a name"
        if (cap == null || cap < 1) return null to "Tier “${t.name}” needs a capacity of at least 1"
        com.etp.app.data.TierInput(t.name.trim(), (t.priceRupees.toIntOrNull() ?: 0) * 100, cap)
    }
    if (parsed.map { it.name.lowercase() }.toSet().size != parsed.size) {
        return null to "Tier names must be unique"
    }
    return CreateEventRequest(
        title = form.title.trim(),
        description = form.description.trim(),
        venue = form.venue.trim(),
        startsAt = startsAt,
        categoryId = form.categoryId!!,
        imageUrl = form.imageUrl.trim(),
        tiers = parsed,
    ) to null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFormFields(form: EventFormState, categories: List<Category>) {
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var categoryMenu by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = form.title,
        onValueChange = { form.title = it },
        label = { Text("Title") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = form.venue,
        onValueChange = { form.venue = it },
        label = { Text("Venue") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))

    ExposedDropdownMenuBox(expanded = categoryMenu, onExpandedChange = { categoryMenu = it }) {
        OutlinedTextField(
            value = categories.firstOrNull { it.id == form.categoryId }?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenu) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
            categories.forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.name) },
                    onClick = {
                        form.categoryId = cat.id
                        categoryMenu = false
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    val selectedDate = form.dateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = selectedDate?.format(DateTimeFormatter.ofPattern("d MMM yyyy")) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Date") },
            trailingIcon = {
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = "Pick date")
                }
            },
            modifier = Modifier.weight(1.4f),
        )
        OutlinedTextField(
            value = form.time,
            onValueChange = { form.time = it },
            label = { Text("Time (HH:mm)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = form.imageUrl,
        onValueChange = { form.imageUrl = it },
        label = { Text("Cover image URL (optional)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = form.description,
        onValueChange = { form.description = it },
        label = { Text("Description") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = form.dateMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    form.dateMillis = pickerState.selectedDateMillis
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** Dynamic tier rows for the Create form. */
@Composable
fun TierEditor(tiers: List<TierRowState>, onAdd: () -> Unit, onRemove: (Int) -> Unit) {
    Text("Ticket tiers", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    tiers.forEachIndexed { index, tier ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = tier.name,
                onValueChange = { tier.name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.weight(1.2f),
            )
            OutlinedTextField(
                value = tier.priceRupees,
                onValueChange = { input -> tier.priceRupees = input.filter(Char::isDigit) },
                label = { Text("₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.8f),
            )
            OutlinedTextField(
                value = tier.capacity,
                onValueChange = { input -> tier.capacity = input.filter(Char::isDigit) },
                label = { Text("Seats") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.8f),
            )
        }
        if (tiers.size > 1) {
            TextButton(onClick = { onRemove(index) }) { Text("Remove tier") }
        }
        Spacer(Modifier.height(8.dp))
    }
    TextButton(onClick = onAdd, enabled = tiers.size < 10) { Text("+ Add tier") }
}
