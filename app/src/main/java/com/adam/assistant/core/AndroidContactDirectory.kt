package com.adam.assistant.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidContactDirectory(private val context: Context) : ContactDirectory {

    override suspend fun load(): List<ContactCandidate> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            Contacts.load(context)
        }
    }

    override fun match(
        entries: List<ContactCandidate>,
        heard: String
    ): ContactMatch = Contacts.match(entries, heard)
}
