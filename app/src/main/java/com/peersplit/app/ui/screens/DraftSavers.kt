package com.peersplit.app.ui.screens

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap

internal fun <T> draftMapSaver() = Saver<SnapshotStateMap<String, T>, HashMap<String, T>>(
    save = { HashMap(it) },
    restore = { mutableStateMapOf<String, T>().apply { putAll(it) } }
)

internal val draftListSaver = Saver<SnapshotStateList<String>, ArrayList<String>>(
    save = { ArrayList(it) },
    restore = { mutableStateListOf<String>().apply { addAll(it) } }
)
