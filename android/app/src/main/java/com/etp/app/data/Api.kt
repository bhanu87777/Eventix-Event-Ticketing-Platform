package com.etp.app.data

import com.etp.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface EtpApi {
    @POST("auth/register") suspend fun register(@Body body: RegisterRequest): AuthResponse
    @POST("auth/login") suspend fun login(@Body body: LoginRequest): AuthResponse

    @GET("events") suspend fun events(@Query("q") query: String? = null): EventsResponse
    @GET("events/{id}") suspend fun event(@Path("id") id: Long): EventResponse
    @POST("events") suspend fun createEvent(@Body body: CreateEventRequest): EventResponse

    @POST("events/{id}/purchase")
    suspend fun purchase(@Path("id") id: Long, @Header("Idempotency-Key") idempotencyKey: String): TicketResponse

    @GET("me/tickets") suspend fun myTickets(): TicketsResponse

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
