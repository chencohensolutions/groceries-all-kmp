package com.hencohen.grocery.data

import app.cash.sqldelight.db.SqlDriver

/** Platform hook: Android passes a Context-backed driver, iOS a native one. */
fun interface DriverFactory {
    fun create(): SqlDriver
}

const val DATABASE_NAME = "grocery.db"
