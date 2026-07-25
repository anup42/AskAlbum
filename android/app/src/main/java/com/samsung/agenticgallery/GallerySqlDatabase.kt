package com.samsung.agenticgallery

import android.content.ContentValues
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteQueryBuilder
import androidx.sqlite.db.SupportSQLiteDatabase

/** Narrow compatibility wrapper used while repository queries move to typed Room DAOs. */
internal class GallerySqlDatabase(private val delegate: SupportSQLiteDatabase) {
    fun <T> transaction(block: (GallerySqlDatabase) -> T): T {
        beginTransaction()
        return try {
            block(this).also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    fun beginTransaction() = delegate.beginTransaction()
    fun setTransactionSuccessful() = delegate.setTransactionSuccessful()
    fun endTransaction() = delegate.endTransaction()
    fun execSQL(sql: String) = delegate.execSQL(sql)
    fun execSQL(sql: String, bindArgs: Array<Any?>) = delegate.execSQL(sql, bindArgs)
    fun rawQuery(sql: String, args: Array<String>?): Cursor = if (args == null) delegate.query(sql) else delegate.query(sql, args)
    fun query(
        table: String,
        columns: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        groupBy: String?,
        having: String?,
        orderBy: String?,
        limit: String? = null,
    ): Cursor = delegate.query(
        SQLiteQueryBuilder.buildQueryString(false, table, columns, selection, groupBy, having, orderBy, limit),
        selectionArgs ?: emptyArray(),
    )

    fun insert(table: String, nullColumnHack: String?, values: ContentValues): Long =
        delegate.insert(table, SQLiteDatabase.CONFLICT_NONE, values)

    fun insertWithOnConflict(table: String, nullColumnHack: String?, values: ContentValues, conflict: Int): Long =
        delegate.insert(table, conflict, values)

    fun insertOrThrow(table: String, nullColumnHack: String?, values: ContentValues): Long =
        delegate.insert(table, SQLiteDatabase.CONFLICT_ABORT, values).also { if (it < 0) throw SQLException("Insert failed for $table") }

    fun update(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?): Int =
        delegate.update(table, SQLiteDatabase.CONFLICT_NONE, values, whereClause, whereArgs)

    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int = delegate.delete(table, whereClause, whereArgs)
}
