package com.etp.app.data

import com.etp.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface EtpApi {
    @POST("auth/register") suspend fun register(@Body body: RegisterRequest): AuthResponse
    @POST("auth/login") suspend fun login(@Body body: LoginRequest): AuthResponse

    // Events + categories
    @GET("categories") suspend fun categories(): CategoriesResponse
    @GET("events") suspend fun events(
        @Query("q") query: String? = null,
        @Query("categoryId") categoryId: Long? = null,
        @Query("sort") sort: String? = null, // date_asc | date_desc | price_asc | price_desc
        @Query("favorites") favorites: Boolean? = null,
        @Query("mine") mine: Boolean? = null,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): EventsResponse
    @GET("events/{id}") suspend fun event(@Path("id") id: Long): EventResponse
    @POST("events") suspend fun createEvent(@Body body: CreateEventRequest): EventResponse

    // Commerce
    @POST("orders/quote") suspend fun quoteOrder(@Body body: QuoteRequest): QuoteResponse
    @POST("orders") suspend fun createOrder(
        @Body body: CreateOrderRequest,
        @Header("Idempotency-Key") idempotencyKey: String,
    ): OrderResponse
    @POST("orders/{id}/confirm") suspend fun confirmOrder(
        @Path("id") id: Long,
        @Body body: ConfirmOrderRequest,
    ): OrderResponse
    @GET("orders/{id}") suspend fun order(@Path("id") id: Long): OrderResponse

    // Tickets
    @GET("me/tickets") suspend fun myTickets(): TicketsResponse
    @POST("tickets/{id}/cancel") suspend fun cancelTicket(@Path("id") id: Long): TicketResponse

    // Favorites
    @POST("events/{id}/favorite") suspend fun favorite(@Path("id") id: Long): Response<Unit>
    @DELETE("events/{id}/favorite") suspend fun unfavorite(@Path("id") id: Long): Response<Unit>

    // Waitlist
    @POST("events/{id}/waitlist") suspend fun joinWaitlist(@Path("id") id: Long): WaitlistJoinResponse
    @DELETE("events/{id}/waitlist") suspend fun leaveWaitlist(@Path("id") id: Long): Response<Unit>
    @GET("me/waitlist") suspend fun myWaitlist(): MyWaitlistResponse

    // Organizer
    @PATCH("events/{id}") suspend fun updateEvent(@Path("id") id: Long, @Body body: PatchEventRequest): Response<Unit>
    @POST("events/{id}/cancel") suspend fun cancelEvent(@Path("id") id: Long): Response<Unit>
    @GET("events/{id}/stats") suspend fun eventStats(@Path("id") id: Long): EventStatsResponse
    @GET("events/{id}/attendees") suspend fun attendees(
        @Path("id") id: Long,
        @Query("q") query: String? = null,
        @Query("limit") limit: Int = 100,
    ): AttendeesResponse
    @GET("events/{id}/attendees.csv") suspend fun attendeesCsv(@Path("id") id: Long): ResponseBody

    // Profile + notifications
    @GET("me") suspend fun me(): UserResponse
    @PATCH("me") suspend fun updateProfile(@Body body: UpdateProfileRequest): UpdateProfileResponse
    @POST("me/password") suspend fun changePassword(@Body body: ChangePasswordRequest): Response<Unit>
    @GET("me/notifications") suspend fun notifications(): NotificationsResponse
    @GET("me/notifications/unread-count") suspend fun unreadCount(): UnreadCountResponse
    @POST("me/notifications/read-all") suspend fun markAllRead(): Response<Unit>

    @POST("checkin") suspend fun checkin(@Body body: CheckinRequest): Response<CheckinResponse>
}

val apiJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

fun buildApi(session: SessionManager): EtpApi {
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = session.token
            val request = if (token != null) {
                chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            } else chain.request()
            chain.proceed(request)
        }
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()

    return Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL + "/")
        .client(client)
        .addConverterFactory(apiJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(EtpApi::class.java)
}
