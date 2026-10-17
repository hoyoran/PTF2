package com.example

import android.app.Application
import com.example.utils.AppLogger

class PtfApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize AppLogger for crash catching and runtime logs
        AppLogger.init(this)
        AppLogger.info("PTF_APP", "Application onCreate completed.")
    }
}
