package com.etp.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.etp.app.data.OrderResponse
import com.etp.app.data.Repository
import com.etp.app.ui.components.CenteredLoader
import com.etp.app.ui.components.etpViewModel
import com.etp.app.ui.theme.SuccessGreen
import com.etp.app.util.formatMoney
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class OrderResultViewModel(private val repo: Repository) : ViewModel() {
    val order = MutableStateFlow<OrderResponse?>(null)

    fun load(orderId: Long) {
        viewModelScope.launch {
            repo.order(orderId).onSuccess { order.value = it }
            repo.myTickets() // refresh the offline wallet cache
        }
    }
}

@Composable
fun OrderResultScreen(orderId: Long, onViewTickets: () -> Unit, onDone: () -> Unit) {
    val vm = etpViewModel { OrderResultViewModel(it.repository) }
    val order by vm.order.collectAsState()

    LaunchedEffect(orderId) { vm.load(orderId) }

    val o = order
    if (o == null) {
        CenteredLoader()
        return
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = SuccessGreen,
            modifier = Modifier.size(88.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text("You're going! 🎉", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "${o.tickets.size} ticket${if (o.tickets.size == 1) "" else "s"} for “${o.order.eventTitle}” — " +
                "${formatMoney(o.order.totalCents, o.order.currency)} paid.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onViewTickets, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("View tickets")
        }
        TextButton(onClick = onDone, modifier = Modifier.padding(top = 8.dp)) {
            Text("Keep browsing")
        }
    }
}
