package com.aegis.localagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class RagHit(
    val title: String,
    val sourceType: String,
    val snippet: String
)

data class RagDocRecord(
    val title: String,
    val sourceType: String,
    val content: String,
    val selected: Boolean
)

class LocalRagStore(context: Context) :
    SQLiteOpenHelper(context, "aegis_local_rag.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS docs_fts USING fts5(title, source_type, content)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS docs_state (title TEXT PRIMARY KEY, selected INTEGER NOT NULL DEFAULT 1)"
        )
        seedDefaultDocs(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS docs_fts")
        db.execSQL("DROP TABLE IF EXISTS docs_state")
        onCreate(db)
    }

    fun indexDocument(title: String, sourceType: String, content: String) {
        writableDatabase.delete("docs_fts", "title = ?", arrayOf(title))
        writableDatabase.insert(
            "docs_fts",
            null,
            ContentValues().apply {
                put("title", title)
                put("source_type", sourceType)
                put("content", content)
            }
        )
        writableDatabase.insertWithOnConflict(
            "docs_state",
            null,
            ContentValues().apply {
                put("title", title)
                put("selected", 1)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun toggleDocumentSelected(title: String, selected: Boolean) {
        writableDatabase.insertWithOnConflict(
            "docs_state",
            null,
            ContentValues().apply {
                put("title", title)
                put("selected", if (selected) 1 else 0)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteDocument(title: String) {
        writableDatabase.delete("docs_fts", "title = ?", arrayOf(title))
        writableDatabase.delete("docs_state", "title = ?", arrayOf(title))
    }

    fun listAllDocuments(): List<RagDocRecord> {
        val selectedMap = mutableMapOf<String, Boolean>()
        readableDatabase.rawQuery("SELECT title, selected FROM docs_state", null).use { c ->
            while (c.moveToNext()) {
                selectedMap[c.getString(0)] = c.getInt(1) == 1
            }
        }
        val docs = mutableListOf<RagDocRecord>()
        readableDatabase.rawQuery("SELECT title, source_type, content FROM docs_fts", null).use { c ->
            while (c.moveToNext()) {
                val title = c.getString(0)
                docs.add(
                    RagDocRecord(
                        title = title,
                        sourceType = c.getString(1),
                        content = c.getString(2),
                        selected = selectedMap[title] ?: true
                    )
                )
            }
        }
        return docs
    }

    fun searchFts5(query: String, limit: Int = 4, onlySelected: Boolean = true): List<RagHit> {
        val cleaned = query
            .replace(Regex("[^a-zA-Z0-9\s]"), " ")
            .trim()
            .split(Regex("\s+"))
            .filter { it.length >= 2 }
            .joinToString(" OR ")
        if (cleaned.isBlank()) return emptyList()

        val selectedTitles = if (onlySelected) {
            listAllDocuments().filter { it.selected }.map { it.title }.toSet()
        } else {
            emptySet()
        }

        val hits = mutableListOf<RagHit>()
        val sql = "SELECT title, source_type, snippet(docs_fts, 2, '[', ']', '...', 18) " +
            "FROM docs_fts WHERE docs_fts MATCH ? ORDER BY rank LIMIT ?"
        readableDatabase.rawQuery(sql, arrayOf(cleaned, (limit * 2).toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val title = cursor.getString(0)
                if (onlySelected && !selectedTitles.contains(title)) continue
                hits.add(
                    RagHit(
                        title = title,
                        sourceType = cursor.getString(1),
                        snippet = cursor.getString(2)
                    )
                )
                if (hits.size >= limit) break
            }
        }
        return hits
    }

    private fun seedDefaultDocs(db: SQLiteDatabase) {
        val seeds = listOf(
            Triple(
                "Q4_Commute_Expenses.pdf",
                "pdf",
                "Monthly rail pass is 118 dollars. Parking passes average 64 dollars monthly. Quarterly cap is 680 dollars."
            ),
            Triple(
                "OnDevice_Inference_Notes.md",
                "note",
                "Gemma 3 1B handles fast 1-to-2 step tool calling. Gemma 3n E2B handles 3-to-6 step ReAct, local FTS5 RAG, and MCP chaining."
            )
        )
        seeds.forEach { (t, s, c) ->
            db.insert(
                "docs_fts",
                null,
                ContentValues().apply {
                    put("title", t)
                    put("source_type", s)
                    put("content", c)
                }
            )
            db.insertWithOnConflict(
                "docs_state",
                null,
                ContentValues().apply {
                    put("title", t)
                    put("selected", 1)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }
}
