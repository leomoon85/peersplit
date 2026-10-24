package com.peersplit.app.util

import org.junit.Assert.*
import org.junit.Test

class PaymentAppCatalogTest {
    @Test fun onlyDetectsLaunchableSupportedAppsAndNeverQueriesOtherPackages() {
        val queried = mutableListOf<String>()
        val visible = setOf("com.samsung.android.spay", "com.paypal.android.p2pmobile", "unrelated.private.app")
        val result = PaymentAppCatalog.installedApps { queried.add(it); it in visible }
        assertEquals(setOf("com.samsung.android.spay", "com.paypal.android.p2pmobile"), result.map { it.packageName }.toSet())
        assertFalse(queried.contains("unrelated.private.app"))
    }
    @Test fun blockedVisibilityDoesNotPreventOtherInstalledAppsFromAppearing() {
        val result = PaymentAppCatalog.installedApps {
            if (it == "com.google.android.apps.walletnfcrel") throw SecurityException("Package hidden")
            it == "com.paypal.android.p2pmobile"
        }
        assertEquals(listOf("com.paypal.android.p2pmobile"), result.map { it.packageName })
    }
    @Test fun noSupportedAppsAndUnknownLaunchTargetsAreHandled() {
        assertTrue(PaymentAppCatalog.installedApps { false }.isEmpty())
        assertFalse(PaymentAppCatalog.isSupported("unrelated.private.app"))
        assertFalse(PaymentAppCatalog.isSupported(""))
    }
}
