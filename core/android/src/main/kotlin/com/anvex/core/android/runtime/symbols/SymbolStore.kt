package com.anvex.core.android.runtime.symbols

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.anvex.core.engine.codeintel.IndexedSymbol
import com.anvex.core.engine.codeintel.SymbolKind

/**
 * P8 (F2b): the symbol index store — a dedicated SQLite file (NOT Room,
 * per the plan) holding extracted definitions + per-file content
 * hashes for incremental re-indexing. Plain tables only; the
 * framework-SQLite restriction that pushed sqlite-vec into its own
 * library does not apply here (no extension is loaded).
 */
class SymbolStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "symbols.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sym_files (" +
                "project TEXT NOT NULL, path TEXT NOT NULL, hash TEXT NOT NULL, " +
                "PRIMARY KEY (project, path))",
        )
        db.execSQL(
            "CREATE TABLE sym_defs (" +
                "project TEXT NOT NULL, name TEXT NOT NULL, kind INTEGER NOT NULL, " +
                "path TEXT NOT NULL, start_off INTEGER NOT NULL, " +
                "end_off INTEGER NOT NULL, line INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX idx_sym_defs_name ON sym_defs (project, name)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS sym_defs")
        db.execSQL("DROP TABLE IF EXISTS sym_files")
        onCreate(db)
    }

    fun fileHash(project: String, path: String): String? {
        readableDatabase.query(
            "sym_files", arrayOf("hash"), "project = ? AND path = ?",
            arrayOf(project, path), null, null, null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    /** Atomically replaces one file's definitions + hash. */
    @Synchronized
    fun replaceFile(project: String, path: String, hash: String, defs: List<IndexedSymbol>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("sym_defs", "project = ? AND path = ?", arrayOf(project, path))
            for (def in defs) {
                db.insert(
                    "sym_defs", null,
                    ContentValues().apply {
                        put("project", project)
                        put("name", def.name)
                        put("kind", kindToWire(def.kind))
                        put("path", def.path)
                        put("start_off", def.startOffset)
                        put("end_off", def.endOffset)
                        put("line", def.line)
                    },
                )
            }
            db.insertWithOnConflict(
                "sym_files", null,
                ContentValues().apply {
                    put("project", project)
                    put("path", path)
                    put("hash", hash)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun removeFile(project: String, path: String) {
        val db = writableDatabase
        db.delete("sym_defs", "project = ? AND path = ?", arrayOf(project, path))
        db.delete("sym_files", "project = ? AND path = ?", arrayOf(project, path))
    }

    fun definitions(project: String, name: String): List<IndexedSymbol> {
        val out = ArrayList<IndexedSymbol>()
        readableDatabase.query(
            "sym_defs", arrayOf("kind", "path", "start_off", "end_off", "line"),
            "project = ? AND name = ?", arrayOf(project, name),
            null, null, "path, start_off",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = SymbolKind.fromWire(cursor.getInt(0)) ?: continue
                out += IndexedSymbol(
                    name = name,
                    kind = kind,
                    path = cursor.getString(1),
                    startOffset = cursor.getInt(2),
                    endOffset = cursor.getInt(3),
                    line = cursor.getInt(4),
                )
            }
        }
        return out
    }

    @Synchronized
    fun clearProject(project: String) {
        val db = writableDatabase
        db.delete("sym_defs", "project = ?", arrayOf(project))
        db.delete("sym_files", "project = ?", arrayOf(project))
    }

    private fun kindToWire(kind: SymbolKind): Int = when (kind) {
        SymbolKind.FUNCTION -> 1
        SymbolKind.TYPE -> 2
        SymbolKind.PROPERTY -> 3
    }
}
