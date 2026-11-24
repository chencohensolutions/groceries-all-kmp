package com.hencohen.grocery

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.hencohen.grocery.data.DATABASE_NAME
import com.hencohen.grocery.data.DriverFactory
import com.hencohen.grocery.db.GroceryDatabase

class AndroidDriverFactory(private val context: Context) : DriverFactory {
    override fun create(): SqlDriver =
        AndroidSqliteDriver(GroceryDatabase.Schema, context.applicationContext, DATABASE_NAME)
}
