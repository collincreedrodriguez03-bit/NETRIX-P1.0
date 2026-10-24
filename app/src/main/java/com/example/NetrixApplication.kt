package com.example

import android.app.Application
import android.util.Log
import java.io.File

class NetrixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashLog = Log.getStackTraceString(throwable)
                val crashFile = File(filesDir, "last_crash.txt")
                crashFile.writeText(crashLog)
            } catch (t: Throwable) {
                Log.e("NetrixApplication", "Failed to write crash log: ${t.message}", t)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
