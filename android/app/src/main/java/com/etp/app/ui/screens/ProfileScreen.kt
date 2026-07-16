package com.etp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.etp.app.data.Repository
import com.etp.app.data.SettingsManager
import com.etp.app.data.ThemeMode
import com.etp.app.data.User
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.SuccessGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(private val repo: Repository, val settings: SettingsManager) : ViewModel() {
    val savingName = MutableStateFlow(false)
    val nameSaved = MutableStateFlow(false)
    val nameError = MutableStateFlow<String?>(null)
    val savingPassword = MutableStateFlow(false)
    val passwordSaved = MutableStateFlow(false)
    val passwordError = MutableStateFlow<String?>(null)

    fun saveName(name: String) {
        if (savingName.value) return
        viewModelScope.launch {
            savingName.value = true
            nameError.value = null
            nameSaved.value = false
            repo.updateProfile(name)
                .onSuccess { nameSaved.value = true }
                .onFailure { nameError.value = it.message }
            savingName.value = false
        }
    }

    fun changePassword(current: String, new: String) {
        if (savingPassword.value) return
        viewModelScope.launch {
            savingPassword.value = true
            passwordError.value = null
            passwordSaved.value = false
            repo.changePassword(current, new)
                .onSuccess { passwordSaved.value = true }
                .onFailure { passwordError.value = it.message }
            savingPassword.value = false
        }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun logout() {
        viewModelScope.launch { repo.logout() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(user: User, onBack: () -> Unit) {
    val vm = etpViewModel { ProfileViewModel(it.repository, it.settings) }
    val savingName by vm.savingName.collectAsState()
    val nameSaved by vm.nameSaved.collectAsState()
    val nameError by vm.nameError.collectAsState()
    val savingPassword by vm.savingPassword.collectAsState()
    val passwordSaved by vm.passwordSaved.collectAsState()
    val passwordError by vm.passwordError.collectAsState()
    val themeMode by vm.settings.themeMode.collectAsState()

    var name by rememberSaveable { mutableStateOf(user.name) }
    var currentPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var localPwError by rememberSaveable { mutableStateOf<String?>(null) }

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
            Text("Profile & settings", style = MaterialTheme.typography.headlineSmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(56.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        user.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text(user.email, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (user.isOrganizer) "Organizer account" else "Attendee account",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Name ----
        Text("Display name", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            supportingText = {
                when {
                    nameError != null -> Text(nameError!!, color = MaterialTheme.colorScheme.error)
                    nameSaved -> Text("Saved", color = SuccessGreen)
                }
            },
        )
        Button(
            onClick = { vm.saveName(name) },
            enabled = !savingName && name.isNotBlank() && name.trim() != user.name,
            modifier = Modifier.align(Alignment.End),
        ) {
            if (savingName) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text("Save name")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // ---- Theme ----
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { vm.setTheme(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                ) {
                    Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        // ---- Password ----
        Text("Change password", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = currentPassword,
            onValueChange = { currentPassword = it },
            label = { Text("Current password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it },
                label = { Text("New password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
        }
        (localPwError ?: passwordError)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        if (passwordSaved) {
            Text("Password changed", color = SuccessGreen, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        Button(
            onClick = {
                localPwError = null
                when {
                    newPassword.length < 8 -> localPwError = "New password must be at least 8 characters"
                    newPassword != confirmPassword -> localPwError = "The new passwords don't match"
                    else -> {
                        vm.changePassword(currentPassword, newPassword)
                        currentPassword = ""
                        newPassword = ""
                        confirmPassword = ""
                    }
                }
            },
            enabled = !savingPassword && currentPassword.isNotBlank() && newPassword.isNotBlank(),
            modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
        ) {
            if (savingPassword) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            } else {
                Text("Change password")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        OutlinedButton(
            onClick = { vm.logout() },
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text("  Sign out", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(32.dp))
    }
}
