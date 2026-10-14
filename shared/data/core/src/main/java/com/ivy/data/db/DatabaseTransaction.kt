package com.ivy.data.db

import androidx.room.withTransaction
import javax.inject.Inject

/** Keeps a ledger operation and its wallet transaction in the same Room commit. */
class DatabaseTransaction @Inject constructor(private val database: IvyRoomDatabase) {
    suspend operator fun <T> invoke(block: suspend () -> T): T = database.withTransaction(block)
}
