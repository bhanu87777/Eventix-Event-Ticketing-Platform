package com.etp.app.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val currencySymbols = mapOf("INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£")

fun formatMoney(priceCents: Int, currency: String): String {
    if (priceCents == 0) return "Free"
    val symbol = currencySymbols[currency] ?: "$currency "
    val whole = priceCents / 100
    val grouped = String.format(Locale.US, "%,d", whole)
    return "$symbol$grouped"
}

private val dateTimeFormat = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a", Locale.ENGLISH)
private val dateOnlyFormat = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
private val timeOnlyFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

fun formatEventDateTime(iso: String): String = runCatching {
    dateTimeFormat.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso)

fun formatEventDate(iso: String): String = runCatching {
    dateOnlyFormat.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso)

fun formatEventTime(iso: String): String = runCatching {
    timeOnlyFormat.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault("")
