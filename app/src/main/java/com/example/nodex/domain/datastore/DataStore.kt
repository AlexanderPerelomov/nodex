package com.example.nodex.domain.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

class Store(private val dataStore: DataStore<Preferences>) {

    suspend fun getAddress(default: String = ADDRESS_DEFAULT): String {
        return dataStore
            .data
            .map { it.toPreferences()[addressKey] }
            .firstOrNull()
            .let {
                if (it.isNullOrBlank()) {
                    default
                } else {
                    it
                }
            }
    }

    suspend fun getCid(default: String = CID_DEFAULT): String {
        return dataStore
            .data
            .map { it.toPreferences()[cidKey] }
            .firstOrNull()
            .let {
                if (it.isNullOrBlank()) {
                    default
                } else {
                    it
                }
            }
    }

    suspend fun getPollingInterval(default: Long = POLLING_INTERVAL_DEFAULT_MS): Long {
        return dataStore
            .data
            .map { it.toPreferences()[pollingIntervalKey] }
            .firstOrNull()
            ?: default
    }

    suspend fun updateAddress(value: String) {
        dataStore.updateData {
            it.toMutablePreferences().also { preferences ->
                preferences[addressKey] = value
            }
        }
    }

    suspend fun updateCid(value: String) {
        dataStore.updateData {
            it.toMutablePreferences().also { preferences ->
                preferences[cidKey] = value
            }
        }
    }

    suspend fun updatePollingInterval(value: Long) {
        dataStore.updateData {
            it.toMutablePreferences().also { preferences ->
                preferences[pollingIntervalKey] = value
            }
        }
    }

    companion object {
        private const val PREFERENCE_KEY_ADDRESS = "PREFERENCE_KEY_ADDRESS"
        private const val PREFERENCE_KEY_CID = "PREFERENCE_KEY_CID"
        private const val PREFERENCE_KEY_POLLING_INTERVAL = "PREFERENCE_KEY_POLLING_INTERVAL"
        val addressKey = stringPreferencesKey(PREFERENCE_KEY_ADDRESS)
        val cidKey = stringPreferencesKey(PREFERENCE_KEY_CID)
        val pollingIntervalKey = longPreferencesKey(PREFERENCE_KEY_POLLING_INTERVAL)

        private const val ADDRESS_DEFAULT = "/dns4/ipfs.infra.cf.team/tcp/4001/p2p/12D3KooWKiqj21VphU2eE25438to5xeny6eP6d3PXT93ZczagPLT"
        private const val CID_DEFAULT = "QmTBimFzPPP2QsB7TQGc2dr4BZD4i7Gm2X1mNtb6DqN9Dr"
        private const val POLLING_INTERVAL_DEFAULT_MS = 3000L
    }
}