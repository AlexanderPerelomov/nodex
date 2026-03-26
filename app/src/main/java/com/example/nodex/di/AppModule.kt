package com.example.nodex.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.example.nodex.domain.datastore.Store
import com.example.nodex.domain.service.ipfs.IPFSService
import com.example.nodex.domain.service.ipfs.IPFSServiceImpl
import com.example.nodex.utils.dataStore
import com.example.nodex.ui.main.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.androidx.viewmodel.dsl.viewModelOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    single<DataStore<Preferences>> {
        get<Context>().dataStore
    }
    singleOf(::Store)
    singleOf(::IPFSServiceImpl).bind<IPFSService>()

    viewModelOf(::MainViewModel)
}
