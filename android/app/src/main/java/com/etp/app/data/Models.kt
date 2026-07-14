package com.etp.app.data

import kotlinx.serialization.Serializable

@Serializable
data class User(val id: Long, val email: String, val name: String, val role: String) {
    val isOrganizer get() = role == "organizer"
}

@Serializable
data class AuthResponse(val token: String, val user: User)

@Serializable
data class Event(
    val id: Long,
    val title: String,
    val description: String = "",
    val venue: String,
    val startsAt: String,
    val priceCents: Int,
    val currency: String = "INR",
    val capacity: Int,
    val ticketsSold: Int,
    val seatsLeft: Int,
    val imageUrl: String = "",
    val organizerId: Long,
    val organizerName: String = "",
)

@Serializable
data class Ticket(
    val id: Long,
    val code: String,
    val status: String,
    val checkedInAt: String? = null,
    val purchasedAt: String,
    val eventId: Long,
    val eventTitle: String,
    val venue: String,
    val startsAt: String,
    val imageUrl: String = "",
    val qr: String,
) {
    val isCheckedIn get() = status == "checked_in"
}

@Serializable data class EventsResponse(val events: List<Event>)
@Serializable data class EventResponse(val event: Event)
@Serializable data class TicketResponse(val ticket: Ticket, val replayed: Boolean = false)
@Serializable data class TicketsResponse(val tickets: List<Ticket>)

@Serializable
data class CheckinResponse(
    val result: String,
    val error: String? = null,
    val attendeeName: String? = null,
    val eventTitle: String? = null,
    val checkedInAt: String? = null,
)

@Serializable data class ApiError(val error: String? = null)

@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class RegisterRequest(val email: String, val password: String, val name: String, val role: String)
@Serializable data class CheckinRequest(val qr: String)

@Serializable
data class CreateEventRequest(
    val title: String,
    val description: String,
    val venue: String,
    val startsAt: String,
    val priceCents: Int,
    val capacity: Int,
    val imageUrl: String = "",
)
