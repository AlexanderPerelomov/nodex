package com.example.nodex.utils

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

private const val APP_PREFERENCES = "APP_PREFERENCES"

val Context.dataStore by preferencesDataStore(name = APP_PREFERENCES)
