package com.motointercom

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class annotated for Hilt dependency injection.
 */
@HiltAndroidApp
class IntercomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d("MotoIntercom", "App started")
    }
}
