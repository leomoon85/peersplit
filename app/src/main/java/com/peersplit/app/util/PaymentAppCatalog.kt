package com.peersplit.app.util

data class PaymentApp(val name: String, val packageName: String)

/** Deliberately limited catalog: never enumerate or persist the user's complete app inventory. */
object PaymentAppCatalog {
    val supported = listOf(
        PaymentApp("Google Wallet", "com.google.android.apps.walletnfcrel"),
        PaymentApp("Samsung Wallet / Samsung Pay", "com.samsung.android.spay"),
        PaymentApp("Google Pay", "com.google.android.apps.nbu.paisa.user"),
        PaymentApp("PhonePe", "com.phonepe.app"),
        PaymentApp("Paytm", "net.one97.paytm"),
        PaymentApp("PayPal", "com.paypal.android.p2pmobile"),
        PaymentApp("Revolut", "com.revolut.revolut"),
        PaymentApp("Cash App", "com.squareup.cash")
    )

    fun installedApps(canLaunch: (String) -> Boolean): List<PaymentApp> = supported.filter {
        try { canLaunch(it.packageName) } catch (_: SecurityException) { false }
    }

    fun isSupported(packageName: String): Boolean = supported.any { it.packageName == packageName }
}
