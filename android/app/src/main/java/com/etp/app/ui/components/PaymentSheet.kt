package com.etp.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.etp.app.ui.theme.BrandGradient
import kotlinx.coroutines.delay

sealed interface PaymentState {
    data object Idle : PaymentState
    data object Processing : PaymentState
    data object Declined : PaymentState
    data object TimedOut : PaymentState
}

/**
 * Simulated payment sheet. The fake card number picks the outcome:
 *   …4242 → success (1.5s) · …0002 → declined · …9999 → timeout (10s).
 * Only success invokes [onSuccess] (which confirms the order server-side);
 * declined/timeout leave the pending order intact for a retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentSheet(
    totalLabel: String,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    var card by rememberSaveable { mutableStateOf("4242 4242 4242 4242") }
    var expiry by rememberSaveable { mutableStateOf("12/28") }
    var cvc by rememberSaveable { mutableStateOf("123") }
    var state by rememberSaveable { mutableStateOf<String>("idle") }

    // Simulation driver — runs when the state enters processing.
    LaunchedEffect(state) {
        if (state != "processing") return@LaunchedEffect
        val digits = card.filter { it.isDigit() }
        when {
            digits.endsWith("9999") -> {
                delay(10_000)
                state = "timeout"
            }
            digits.endsWith("0002") -> {
                delay(1_500)
                state = "declined"
            }
            else -> {
                delay(1_500)
                onSuccess()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (state != "processing") onDismiss() }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Payment", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Simulated — no real money moves. Try a card ending 0002 (decline) or 9999 (timeout).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            // Fake card face
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(BrandGradient)
                    .padding(20.dp),
            ) {
                Column(Modifier.align(Alignment.BottomStart)) {
                    Text(
                        card.ifBlank { "•••• •••• •••• ••••" },
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                    )
                    Text("EVENTIX PAY", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                }
            }
            Spacer(Modifier.height(16.dp))

            when (state) {
                "processing" -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("  Contacting your bank…", style = MaterialTheme.typography.bodyLarge)
                }
                "declined" -> PaymentIssue(
                    icon = { Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                    text = "Your card was declined. No charge was made.",
                    retryLabel = "Try another card",
                    onRetry = { state = "idle" },
                )
                "timeout" -> PaymentIssue(
                    icon = { Icon(Icons.Outlined.HourglassEmpty, null, tint = MaterialTheme.colorScheme.error) },
                    text = "The payment timed out. Your order is still reserved — try again.",
                    retryLabel = "Retry payment",
                    onRetry = { state = "idle" },
                )
                else -> {
                    OutlinedTextField(
                        value = card,
                        onValueChange = { input -> card = input.filter { it.isDigit() || it == ' ' }.take(19) },
                        label = { Text("Card number") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = expiry,
                            onValueChange = { expiry = it.take(5) },
                            label = { Text("MM/YY") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = cvc,
                            onValueChange = { input -> cvc = input.filter { it.isDigit() }.take(4) },
                            label = { Text("CVC") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = { state = "processing" },
                        enabled = card.filter { it.isDigit() }.length >= 12,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                    ) {
                        Text("Pay $totalLabel")
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentIssue(icon: @Composable () -> Unit, text: String, retryLabel: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Button(onClick = onRetry) { Text(retryLabel) }
    }
}
