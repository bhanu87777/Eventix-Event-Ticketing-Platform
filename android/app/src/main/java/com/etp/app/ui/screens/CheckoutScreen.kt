package com.etp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.etp.app.data.CreateOrderRequest
import com.etp.app.data.Event
import com.etp.app.data.OrderItemInput
import com.etp.app.data.OrderQuote
import com.etp.app.data.Repository
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.ErrorState
import com.etp.app.ui.components.PaymentSheet
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.formatMoney
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.UUID

class CheckoutViewModel(private val repo: Repository) : ViewModel() {
    val event = MutableStateFlow<Event?>(null)
    val quote = MutableStateFlow<OrderQuote?>(null)
    val promo = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)
    val payError = MutableStateFlow<String?>(null)
    val creating = MutableStateFlow(false)
    val sheetOpen = MutableStateFlow(false)
    val paidOrderId = MutableStateFlow<Long?>(null)

    private var eventId = 0L
    private var tierId = 0L
    private var quantity = 1
    private var offerId: Long? = null
    private var orderId: Long? = null

    /** One key per order *intent* — regenerated when the inputs change. */
    private var idempotencyKey = UUID.randomUUID().toString()

    @OptIn(FlowPreview::class)
    fun start(eventId: Long, tierId: Long, quantity: Int, offerId: Long?) {
        if (this.eventId != 0L) return // already started
        this.eventId = eventId
        this.tierId = tierId
        this.quantity = quantity
        this.offerId = offerId

        viewModelScope.launch {
            repo.event(eventId)
                .onSuccess { event.value = it }
                .onFailure { error.value = it.message }
        }
        requote()
        // Debounced live promo preview; collectLatest = latest-wins, so a slow
        // quote for an old promo can't overwrite a newer one.
        viewModelScope.launch {
            promo.debounce(400).collectLatest {
                // Promo changed → this is a new order intent.
                orderId = null
                idempotencyKey = UUID.randomUUID().toString()
                requoteNow()
            }
        }
    }

    private fun requote() = viewModelScope.launch { requoteNow() }

    private suspend fun requoteNow() {
        repo.quoteOrder(eventId, listOf(OrderItemInput(tierId, quantity)), promo.value)
            .onSuccess { quote.value = it }
            .onFailure { error.value = it.message }
    }

    /** Creates (or reuses) the pending order, then opens the payment sheet. */
    fun pay() {
        if (creating.value) return
        viewModelScope.launch {
            creating.value = true
            payError.value = null
            if (orderId == null) {
                val body = CreateOrderRequest(
                    eventId = eventId,
                    items = listOf(OrderItemInput(tierId, quantity)),
                    promoCode = promo.value.ifBlank { null },
                    waitlistOfferId = offerId,
                )
                repo.createOrder(body, idempotencyKey)
                    .onSuccess { orderId = it.order.id }
                    .onFailure { payError.value = it.message }
            }
            creating.value = false
            if (orderId != null) sheetOpen.value = true
        }
    }

    /** The simulated gateway said yes — confirm server-side and issue seats. */
    fun onPaymentApproved() {
        val id = orderId ?: return
        viewModelScope.launch {
            repo.confirmOrder(id, "success")
                .onSuccess {
                    sheetOpen.value = false
                    paidOrderId.value = it.order.id
                }
                .onFailure {
                    sheetOpen.value = false
                    payError.value = it.message
                    // The order may be dead (expired/sold out) — next attempt starts fresh.
                    orderId = null
                    idempotencyKey = UUID.randomUUID().toString()
                }
        }
    }

    fun dismissSheet() {
        sheetOpen.value = false
    }
}

@Composable
fun CheckoutScreen(
    eventId: Long,
    tierId: Long,
    quantity: Int,
    offerId: Long?,
    onBack: () -> Unit,
    onPaid: (Long) -> Unit,
) {
    val vm = etpViewModel { CheckoutViewModel(it.repository) }
    val event by vm.event.collectAsState()
    val quote by vm.quote.collectAsState()
    val promo by vm.promo.collectAsState()
    val error by vm.error.collectAsState()
    val payError by vm.payError.collectAsState()
    val creating by vm.creating.collectAsState()
    val sheetOpen by vm.sheetOpen.collectAsState()
    val paidOrderId by vm.paidOrderId.collectAsState()

    LaunchedEffect(Unit) { vm.start(eventId, tierId, quantity, offerId) }
    LaunchedEffect(paidOrderId) { paidOrderId?.let(onPaid) }

    when {
        error != null -> ErrorState(error!!, onRetry = null)
        event == null || quote == null -> CenteredLoader()
        else -> {
            val e = event!!
            val q = quote!!
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text("Checkout", style = MaterialTheme.typography.headlineSmall)
                }

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Text(e.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        e.venue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (offerId != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Claiming your held waitlist seat",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(20.dp))

                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Column(Modifier.padding(16.dp)) {
                            q.lineItems.forEach { line ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(
                                        "${line.quantity} × ${line.tierName}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Text(formatMoney(line.totalCents, e.currency), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            if (q.discountCents > 0) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(
                                        "Promo ${promo.uppercase()}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = SuccessGreen,
                                    )
                                    Text("-${formatMoney(q.discountCents, e.currency)}", color = SuccessGreen, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Text("Total", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text(formatMoney(q.totalCents, e.currency), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = promo,
                        onValueChange = { vm.promo.value = it.uppercase() },
                        label = { Text("Promo code") },
                        singleLine = true,
                        supportingText = {
                            when {
                                promo.isBlank() -> {}
                                q.promoValid == true -> Text("Code applied", color = SuccessGreen)
                                q.promoMessage != null -> Text(q.promoMessage!!, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                }

                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 12.dp) {
                    Column(Modifier.navigationBarsPadding().padding(20.dp)) {
                        payError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        Button(
                            onClick = { vm.pay() },
                            enabled = !creating,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            if (creating) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            } else {
                                Text("Pay ${formatMoney(q.totalCents, e.currency)}")
                            }
                        }
                    }
                }
            }

            if (sheetOpen) {
                PaymentSheet(
                    totalLabel = formatMoney(q.totalCents, e.currency),
                    onSuccess = { vm.onPaymentApproved() },
                    onDismiss = { vm.dismissSheet() },
                )
            }
        }
    }
}
