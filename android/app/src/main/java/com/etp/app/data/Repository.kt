package com.etp.app.data

import retrofit2.HttpException
import java.io.IOException

class Repository(private val api: EtpApi, val session: SessionManager) {

    suspend fun login(email: String, password: String) = call {
        api.login(LoginRequest(email.trim(), password)).also { session.save(it) }
    }

    suspend fun register(email: String, password: String, name: String, role: String) = call {
        api.register(RegisterRequest(email.trim(), password, name.trim(), role)).also { session.save(it) }
    }

    suspend fun events(query: String? = null) = call { api.events(query?.ifBlank { null }).events }

    suspend fun event(id: Long) = call { api.event(id).event }

    suspend fun createEvent(body: CreateEventRequest) = call { api.createEvent(body).event }

    suspend fun purchase(eventId: Long, idempotencyKey: String) = call {
        api.purchase(eventId, idempotencyKey).ticket
    }

    suspend fun myTickets() = call { api.myTickets().tickets }

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

    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: HttpException) {
        val message = try {
            e.response()?.errorBody()?.string()?.let { apiJson.decodeFromString<ApiError>(it).error }
        } catch (_: Exception) {
            null
        }
        Result.failure(Exception(message ?: "Request failed (${e.code()})"))
    } catch (e: IOException) {
        Result.failure(Exception(OFFLINE_MESSAGE))
    }

    private companion object {
        const val OFFLINE_MESSAGE =
            "Can't reach the server. Is the backend running and `adb reverse tcp:3000 tcp:3000` set?"
    }
}
