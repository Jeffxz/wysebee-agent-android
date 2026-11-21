package com.wysebee.agent.android.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveTest {
    @Test fun knownOnesByPackage() {
        for (pkg in listOf("com.x8bit.bitwarden", "com.azure.authenticator", "com.google.android.apps.authenticator2", "com.rbc.mobile.android",
            "com.cibc.android.mobi", "jp.ne.paypay.android.app", "com.google.android.apps.walletnfcrel", "com.felicanetworks.mfm.main")) {
            assertTrue(pkg, Sensitive.isSensitive(pkg))
        }
    }

    @Test fun othersByAWordInTheirPackageOrName() {
        for (pkg in listOf("com.scotiabank.banking", "ca.pcfinancial.bank", "com.example.mybank", "com.example.paypal", "com.example.cryptowallet", "org.example.passwords", "com.example.investapp", "com.example.freeotp")) {
            assertTrue(pkg, Sensitive.isSensitive(pkg))
        }
        assertTrue(Sensitive.isSensitive("com.example.app", "Big Bank Mobile"))
        assertTrue(Sensitive.isSensitive("com.example.app", "Password Safe"))
        assertTrue(Sensitive.isSensitive("jp.example.app", "三菱ＵＦＪ銀行"))
        assertTrue(Sensitive.isSensitive("cn.example.app", "支付宝"))
    }

    @Test fun everydayAppsAreNot() {
        for ((pkg, name) in listOf(
            "com.airbnb.android" to "Airbnb", "com.booking" to "Booking.com", "com.xiaomi.calendar" to "Calendar",
            "com.dropbox.android" to "Dropbox", "com.miui.weather2" to "Weather", "jp.co.yahoo.android.ymail" to "Yahoo! Mail",
            "com.google.android.youtube" to "YouTube", "com.miui.calculator" to "Calculator", "com.android.vending" to "Play Store",
            "com.google.android.apps.maps" to "Maps", "com.android.deskclock" to "Clock", "com.example.display" to "Display",
            "com.example.app" to "Payday Planner", "com.mi.globalminusscreen" to "App vault",
        )) {
            assertFalse("$pkg $name", Sensitive.isSensitive(pkg, name))
        }
        assertFalse(Sensitive.isSensitive("com.example.app"))
    }
}
