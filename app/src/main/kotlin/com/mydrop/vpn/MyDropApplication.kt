package com.mydrop.vpn

import android.app.Application
import com.mydrop.vpn.data.AppContainer

class MyDropApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
