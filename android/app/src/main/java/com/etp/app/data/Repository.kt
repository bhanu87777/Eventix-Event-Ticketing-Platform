package com.etp.app.data

import retrofit2.HttpException
import java.io.IOException

/** Wallet result that knows whether it came from the offline cache. */
data class TicketsResult(
    val tickets: List<Ticket>,
    val fromCache: Boolean = false,
    val fetchedAt: Long = 0L,
)

class Repository(
    private val api: EtpApi,
    val session: SessionManager,
    private val ticketCache: TicketCache,
) {

    suspend fun login(email: String, password: String) = call {
        api.login(LoginRequest(email.trim(), password)).also { session.save(it) }
    }

    suspend fun register(email: String, password: String, name: String, role: String) = call {
        api.register(RegisterRequest(email.trim(), password, name.trim(), role)).also { session.save(it) }
    }

    suspend fun logout() {
        ticketCache.clear()
        session.logout()
    }

    // ---- Events -------------------------------------------------------------

    suspend fun categories() = call { api.categories().categories }

    suspend fun events(
        query: String? = null,
        categoryId: Long? = null,
        sort: String? = null,
        favorites: Boolean? = null,
        mine: Boolean? = null,
        limit: Int = 20,
        offset: Int = 0,
    ) = call {
        api.events(query?.ifBlank { null }, categoryId, sort, favorites, mine, limit, offset)
    }

    suspend fun event(id: Long) = call { api.event(id).event }

    suspend fun createEvent(body: CreateEventRequest) = call { api.createEvent(body).event }

    // ---- Commerce -----------------------------------------------------------

    suspend fun quoteOrder(eventId: Long, items: List<OrderItemInput>, promoCode: String?) = call {
        api.quoteOrder(QuoteRequest(eventId, items, promoCode?.ifBlank { null })).quote
    }

    suspend fun createOrder(body: CreateOrderRequest, idempotencyKey: String) = call {
        api.createOrder(body, idempotencyKey)
    }

    suspend fun confirmOrder(orderId: Long, outcome: String) = call {
        api.confirmOrder(orderId, ConfirmOrderRequest(PaymentBody(outcome)))
    }

    suspend fun order(orderId: Long) = call { api.order(orderId) }

    // ---- Tickets (cache-aware wallet) ----------------------------------------

    suspend fun myTickets(): Result<TicketsResult> = try {
        val tickets = api.myTickets().tickets
        ticketCache.write(tickets)
        Result.success(TicketsResult(tickets))
    } catch (e: IOException) {
        val cached = ticketCache.read()
        if (cached != null) {
            Result.success(TicketsResult(cached.first, fromCache = true, fetchedAt = cached.second))
        } else {
            Result.failure(Exception(OFFLINE_MESSAGE))
        }
    } catch (e: HttpException) {
        Result.failure(Exception(httpMessage(e)))
    }

    suspend fun cancelTicket(id: Long) = call { api.cancelTicket(id).ticket }

    // ---- Favorites / waitlist -------------------------------------------------

    suspend fun setFavorite(eventId: Long, favorite: Boolean) = call {
        if (favorite) api.favorite(eventId) else api.unfavorite(eventId)
        favorite
    }

    suspend fun joinWaitlist(eventId: Long) = call { api.joinWaitlist(eventId).entry }
    suspend fun leaveWaitlist(eventId: Long) = call { api.leaveWaitlist(eventId); true }
    suspend fun myWaitlist() = call { api.myWaitlist().entries }

    // ---- Organizer ------------------------------------------------------------

    suspend fun updateEvent(id: Long, body: PatchEventRequest) = call { api.updateEvent(id, body); true }
    suspend fun cancelEvent(id: Long) = call { api.cancelEvent(id); true }
    suspend fun eventStats(id: Long) = call { api.eventStats(id).stats }
    suspend fun attendees(id: Long, query: String? = null) = call {
        api.attendees(id, query?.ifBlank { null })
    }
    suspend fun attendeesCsv(id: Long) = call { api.attendeesCsv(id).string() }

    // ---- Profile + notifications ----------------------------------------------

    suspend fun updateProfile(name: String) = call {
        val res = api.updateProfile(UpdateProfileRequest(name.trim()))
        session.update(res.token, res.user) // JWT embeds the name — swap it
        res.user
    }

    suspend fun changePassword(current: String, new: String) = call {
        api.changePassword(ChangePasswordRequest(current, new))
        true
    }

    suspend fun notifications() = call { api.notifications() }
    suspend fun unreadCount() = call { api.unreadCount().unreadCount }
    suspend fun markAllRead() = call { api.markAllRead(); true }

    /** Check-in errors (already used, forged, wrong event) arrive as non-2xx
     *  bodies that still carry a CheckinResponse — surface them as data. */
    suspend fun checkin(qr: String): Result<CheckinResponse> = try {
        val response = api.checkin(CheckinRequest(qr))
        val body = response.body()
            ?: response.errorBody()?.string()?.let { apiJson.decodeFromString<CheckinResponse>(it) }
            ?: CheckinResponse(result = "invalid", error = "Empty server response")
        Result.success(body)
    } catch (e: IOException) {
        Result.failure(Exception(OFFLINE_MESSAGE))
    }

    private fun httpMessage(e: HttpException): String = try {
        e.response()?.errorBody()?.string()?.let { apiJson.decodeFromString<ApiError>(it).error }
    } catch (_: Exception) {
        null
    } ?: "Request failed (${e.code()})"

    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: HttpException) {
        Result.failure(Exception(httpMessage(e)))
    } catch (e: IOException) {
        Result.failure(Exception(OFFLINE_MESSAGE))
    }

    private companion object {
        const val OFFLINE_MESSAGE =
            "Can't reach the server. Is the backend running and `adb reverse tcp:3000 tcp:3000` set?"
    }
}
