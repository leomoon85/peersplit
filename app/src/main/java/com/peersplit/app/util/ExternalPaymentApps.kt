package com.peersplit.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

object ExternalPaymentApps {
    fun installedApps(context: Context): List<PaymentApp> = PaymentAppCatalog.installedApps { packageName ->
        try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            info.enabled && context.packageManager.getLaunchIntentForPackage(packageName) != null
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
    }

    /** Launcher action only. No recipient, amount, currency, extras, URI or result callback. */
    fun open(context: Context, packageName: String): Boolean {
        if (!PaymentAppCatalog.isSupported(packageName)) return false
        return try {
            val component = context.packageManager.getLaunchIntentForPackage(packageName)?.component
                ?: return false
            if (component.packageName != packageName) return false
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) { false
        } catch (_: SecurityException) { false }
    }
}
