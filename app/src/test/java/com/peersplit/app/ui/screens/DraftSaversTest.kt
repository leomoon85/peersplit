package com.peersplit.app.ui.screens

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class DraftSaversTest {
    private val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
    @Test fun splitPayerAndMemberDraftsSurviveSavedStateRoundTrip() {
        val amounts = mutableStateMapOf("alice" to "40.00", "bob" to "60.00")
        val amountsSaver = draftMapSaver<String>()
        val saved = with(amountsSaver) { scope.save(amounts) }!!
        amounts.clear()
        assertEquals(mapOf("alice" to "40.00", "bob" to "60.00"), amountsSaver.restore(saved)!!.toMap())
        val selected = mutableStateMapOf("alice" to false, "bob" to true)
        val selectedSaver = draftMapSaver<Boolean>()
        assertEquals(selected.toMap(), selectedSaver.restore(with(selectedSaver) { scope.save(selected) }!!)!!.toMap())
        val members = mutableStateListOf("Alice|1234567890", "Bob")
        assertEquals(members.toList(), draftListSaver.restore(with(draftListSaver) { scope.save(members) }!!)!!.toList())
    }
}
