package com.hencohen.grocery.android

import android.app.Application
import com.hencohen.grocery.AndroidDriverFactory
import com.hencohen.grocery.GroceryCore

class GroceryApp : Application() {

    lateinit var core: GroceryCore
        private set

    override fun onCreate() {
        super.onCreate()
        core = GroceryCore(AndroidDriverFactory(this))

        PriceSyncWorker.schedulePeriodic(this)

        // First launch: don't wait up to a week for the first prices.
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_INITIAL_SYNC, false)) {
            PriceSyncWorker.runNow(this)
            prefs.edit().putBoolean(KEY_INITIAL_SYNC, true).apply()
        }
    }

    private companion object {
        const val KEY_INITIAL_SYNC = "initial_sync_requested"
    }
}
