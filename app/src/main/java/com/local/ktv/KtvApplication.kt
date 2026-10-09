package com.local.ktv

import android.app.Application
import android.util.Log

class KtvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instanceRef = this
        PersonalMediaStore.init(this)
        CatalogAssets.init(this)
        Log.i(TAG, "KTV application initialized without native services")
    }

    companion object {
        private const val TAG = "KtvApplication"

        lateinit var instanceRef: KtvApplication
            private set

        @JvmStatic
        fun getInstance(): KtvApplication = instanceRef
    }
}
