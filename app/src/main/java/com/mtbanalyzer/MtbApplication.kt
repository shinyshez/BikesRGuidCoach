package com.mtbanalyzer

import android.app.Application

class MtbApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
