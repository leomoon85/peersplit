package com.peersplit.app.util

object UpiAddress {
    private val format = Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9.-]{1,63}$")
    fun isValid(value: String) = format.matches(value.trim())
    fun requireValidOrEmpty(value: String): String = value.trim().also {
        require(it.isEmpty() || isValid(it)) { "Enter a valid UPI ID (for example name@bank)" }
    }
}
