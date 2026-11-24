package com.hencohen.grocery

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.hencohen.grocery.data.DATABASE_NAME
import com.hencohen.grocery.data.DriverFactory
import com.hencohen.grocery.db.GroceryDatabase

class IosDriverFactory : DriverFactory {
    override fun create(): SqlDriver = NativeSqliteDriver(GroceryDatabase.Schema, DATABASE_NAME)
}
