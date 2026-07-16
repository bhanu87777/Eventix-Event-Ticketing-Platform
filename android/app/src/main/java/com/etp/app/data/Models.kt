package com.etp.app.data

import kotlinx.serialization.Serializable

@Serializable
data class User(val id: Long, val email: String, val name: String, val role: String) {
    val isOrganizer get() = role == "organizer"
}

@Serializable
data class AuthResponse(val token: String, val user: User)

@Serializable
data class TicketTier(
    val id: Long,
    val name: String,
    val priceCents: Int,
    val capacity: Int,
    val sold: Int,
    val seatsLeft: Int,
)

@Serializable
data class WaitlistEntry(
    val id: Long,
    val status: String, // waiting | offered | claimed | expired | left
    val tierId: Long? = null,
    val offerExpiresAt: String? = null,
)

@Serializable
data class Event(
    val id: Long,
    val title: String,
    val description: String = "",
    val venue: String,
    val startsAt: String,
    val priceCents: Int = 0,
    val priceFromCents: Int = 0,
    val currency: String = "INR",
    val capacity: Int,
    val ticketsSold: Int,
    val seatsLeft: Int,
    val imageUrl: String = "",
    val organizerId: Long,
    val organizerName: String = "",
    val status: String = "published", // published | cancelled
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val isFavorite: Boolean = false,
    val tiers: List<TicketTier> = emptyList(),
    val myWaitlist: WaitlistEntry? = null,
) {
    val isCancelled get() = status == "cancelled"
    val minPriceCents get() = if (priceFromCents > 0) priceFromCents else priceCents

    /** Legacy payloads carry no tiers — sell through a synthetic General tier. */
    val sellableTiers: List<TicketTier>
        get() = tiers.ifEmpty {
            listOf(TicketTier(id = 0, name = "General", priceCents = minPriceCents, capacity = capacity, sold = ticketsSold, seatsLeft = seatsLeft))
        }
}

@Serializable
data class Ticket(
    val id: Long,
    val code: String,
    val status: String, // valid | checked_in | cancelled | void
    val checkedInAt: String? = null,
    val purchasedAt: String,
    val eventId: Long,
    val eventTitle: String,
    val venue: String,
    val startsAt: String,
    val imageUrl: String = "",
    val tierName: String = "",
    val priceCents: Int = 0,
    val qr: String,
) {
    val isCheckedIn get() = status == "checked_in"
    val isValid get() = status == "valid"
}

@Serializable data class EventsResponse(
    val events: List<Event>,
    val total: Int = 0,
    val hasMore: Boolean = false,
)
@Serializable data class EventResponse(val event: Event)
@Serializable data class TicketResponse(val ticket: Ticket, val replayed: Boolean = false)
@Serializable data class TicketsResponse(val tickets: List<Ticket>)

@Serializable data class Category(val id: Long, val name: String)
@Serializable data class CategoriesResponse(val categories: List<Category>)

// ---- Commerce -------------------------------------------------------------

@Serializable data class OrderItemInput(val tierId: Long, val quantity: Int)

@Serializable
data class QuoteRequest(
    val eventId: Long,
    val items: List<OrderItemInput>,
    val promoCode: String? = null,
)

@Serializable
data class OrderLine(
    val tierId: Long,
    val tierName: String,
    val quantity: Int,
    val unitPriceCents: Int,
    val totalCents: Int,
)

@Serializable
data class OrderQuote(
    val lineItems: List<OrderLine>,
    val subtotalCents: Int,
    val discountCents: Int,
    val totalCents: Int,
    val promoValid: Boolean? = null,
    val promoMessage: String? = null,
)
@Serializable data class QuoteResponse(val quote: OrderQuote)

@Serializable
data class CreateOrderRequest(
    val eventId: Long,
    val items: List<OrderItemInput>,
    val promoCode: String? = null,
    val waitlistOfferId: Long? = null,
)

@Serializable
data class Order(
    val id: Long,
    val eventId: Long,
    val eventTitle: String = "",
    val status: String, // pending | paid | failed | expired
    val items: List<OrderLine> = emptyList(),
    val subtotalCents: Int,
    val discountCents: Int = 0,
    val totalCents: Int,
    val currency: String = "INR",
    val promoCode: String? = null,
    val expiresAt: String = "",
    val createdAt: String = "",
)

@Serializable
data class OrderResponse(
    val order: Order,
    val tickets: List<Ticket> = emptyList(),
    val replayed: Boolean = false,
)

@Serializable
data class PaymentBody(val outcome: String)
@Serializable
data class ConfirmOrderRequest(val payment: PaymentBody)

// ---- Organizer ------------------------------------------------------------

@Serializable data class SalesPoint(val date: String, val tickets: Int)

@Serializable
data class TierStat(
    val tierId: Long,
    val name: String,
    val sold: Int,
    val capacity: Int,
    val priceCents: Int,
)

@Serializable
data class EventStats(
    val sold: Int,
    val checkedIn: Int,
    val checkinRate: Double,
    val revenueCents: Int,
    val byTier: List<TierStat> = emptyList(),
    val salesByDay: List<SalesPoint> = emptyList(),
)
@Serializable data class EventStatsResponse(val stats: EventStats)

@Serializable
data class Attendee(
    val ticketId: Long,
    val code: String,
    val status: String,
    val checkedInAt: String? = null,
    val purchasedAt: String,
    val tierName: String,
    val userName: String,
    val userEmail: String,
)
@Serializable data class AttendeesResponse(val attendees: List<Attendee>, val total: Int = 0)

@Serializable
data class PatchEventRequest(
    val title: String? = null,
    val description: String? = null,
    val venue: String? = null,
    val startsAt: String? = null,
    val imageUrl: String? = null,
    val categoryId: Long? = null,
)

// ---- Waitlist / notifications / profile ------------------------------------

@Serializable
data class MyWaitlistEntry(
    val id: Long,
    val status: String,
    val tierId: Long? = null,
    val offerExpiresAt: String? = null,
    val joinedAt: String = "",
    val eventId: Long,
    val eventTitle: String,
    val venue: String = "",
    val startsAt: String = "",
    val imageUrl: String = "",
)
@Serializable data class MyWaitlistResponse(val entries: List<MyWaitlistEntry>)
@Serializable data class WaitlistJoinResponse(val entry: WaitlistEntry)

@Serializable
data class AppNotification(
    val id: Long,
    val type: String, // order_paid | event_updated | event_cancelled | waitlist_offer
    val title: String,
    val body: String = "",
    val eventId: Long? = null,
    val orderId: Long? = null,
    val ticketId: Long? = null,
    val offerId: Long? = null,
    val read: Boolean = false,
    val createdAt: String,
)
@Serializable data class NotificationsResponse(
    val notifications: List<AppNotification>,
    val unreadCount: Int = 0,
)
@Serializable data class UnreadCountResponse(val unreadCount: Int = 0)

@Serializable data class UpdateProfileRequest(val name: String)
@Serializable data class UpdateProfileResponse(val user: User, val token: String)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable data class UserResponse(val user: User)

// ---- Misc -----------------------------------------------------------------

@Serializable
data class CheckinResponse(
    val result: String,
    val error: String? = null,
    val attendeeName: String? = null,
    val eventTitle: String? = null,
    val checkedInAt: String? = null,
)

@Serializable data class ApiError(val error: String? = null, val code: String? = null)

@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class RegisterRequest(val email: String, val password: String, val name: String, val role: String)
@Serializable data class CheckinRequest(val qr: String)

@Serializable data class TierInput(val name: String, val priceCents: Int, val capacity: Int)

@Serializable
data class CreateEventRequest(
    val title: String,
    val description: String,
    val venue: String,
    val startsAt: String,
    val categoryId: Long,
    val imageUrl: String = "",
    val tiers: List<TierInput> = emptyList(),
)
