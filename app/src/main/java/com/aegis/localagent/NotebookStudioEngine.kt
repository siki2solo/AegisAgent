package com.aegis.localagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class AudioTurn(
    val speaker: String,
    val text: String
)

data class NotebookQaResult(
    val answer: String,
    val citations: List<RagHit>
)

data class ChatThreadEntity(
    val id: String,
    val title: String,
    val mode: String
)

data class ChatMessageEntity(
    val id: String,
    val threadId: String,
    val isUser: Boolean,
    val text: String,
    val meta: String
)

class LocalThreadStore(context: Context) :
    SQLiteOpenHelper(context, "aegis_threads.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS threads (id TEXT PRIMARY KEY, title TEXT NOT NULL, mode TEXT NOT NULL, updated_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, is_user INTEGER NOT NULL, text TEXT NOT NULL, meta TEXT NOT NULL, seq INTEGER NOT NULL)"
        )
        val defaultId = "thread-default"
        db.insert(
            "threads",
            null,
            ContentValues().apply {
                put("id", defaultId)
                put("title", "General Local Agent")
                put("mode", "auto")
                put("updated_at", System.currentTimeMillis())
            }
        )
        db.insert(
            "messages",
            null,
            ContentValues().apply {
                put("id", "welcome-msg")
                put("thread_id", defaultId)
                put("is_user", 0)
                put("text", "AegisAgent v1.3 ready 100% offline. Gemini-style multi-thread chat, NotebookLM local sources, and deterministic guardrails active.")
                put("meta", "v1.3 · Local Only")
                put("seq", 1)
            }
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS threads")
        onCreate(db)
    }

    fun listThreads(): List<ChatThreadEntity> {
        val out = mutableListOf<ChatThreadEntity>()
        readableDatabase.rawQuery(
            "SELECT id, title, mode FROM threads ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ChatThreadEntity(c.getString(0), c.getString(1), c.getString(2)))
            }
        }
        return out
    }

    fun createThread(title: String, mode: String = "auto"): ChatThreadEntity {
        val id = "thr-${UUID.randomUUID()}"
        writableDatabase.insert(
            "threads",
            null,
            ContentValues().apply {
                put("id", id)
                put("title", title)
                put("mode", mode)
                put("updated_at", System.currentTimeMillis())
            }
        )
        appendMessage(
            threadId = id,
            isUser = false,
            text = "Started new offline thread ($mode mode).",
            meta = "v1.3 · Local"
        )
        return ChatThreadEntity(id, title, mode)
    }

    fun updateThreadMode(threadId: String, mode: String) {
        writableDatabase.update(
            "threads",
            ContentValues().apply {
                put("mode", mode)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(threadId)
        )
    }

    fun listMessages(threadId: String): List<ChatMessageEntity> {
        val out = mutableListOf<ChatMessageEntity>()
        readableDatabase.rawQuery(
            "SELECT id, thread_id, is_user, text, meta FROM messages WHERE thread_id = ? ORDER BY seq ASC",
            arrayOf(threadId)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChatMessageEntity(
                        id = c.getString(0),
                        threadId = c.getString(1),
                        isUser = c.getInt(2) == 1,
                        text = c.getString(3),
                        meta = c.getString(4)
                    )
                )
            }
        }
        return out
    }

    fun appendMessage(threadId: String, isUser: Boolean, text: String, meta: String) {
        val seq = System.currentTimeMillis()
        writableDatabase.insert(
            "messages",
            null,
            ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("thread_id", threadId)
                put("is_user", if (isUser) 1 else 0)
                put("text", text)
                put("meta", meta)
                put("seq", seq)
            }
        )
        writableDatabase.update(
            "threads",
            ContentValues().apply { put("updated_at", seq) },
            "id = ?",
            arrayOf(threadId)
        )
    }
}

class NotebookStudioEngine(private val ragStore: LocalRagStore) {

    fun answerWithCitations(question: String): NotebookQaResult {
        val hits = ragStore.searchFts5(question, limit = 4, onlySelected = true)
        if (hits.isEmpty()) {
            val activeDocs = ragStore.listAllDocuments().filter { it.selected }
            val fallbackMsg = if (activeDocs.isEmpty()) {
                "No sources are currently checked. Check at least one document in Notebook Sources."
            } else {
                "No direct FTS5 snippet match across ${activeDocs.size} checked source(s). Try keywords from your selected files."
            }
            return NotebookQaResult(fallbackMsg, emptyList())
        }
        val body = hits.mapIndexed { idx, hit ->
            "[${idx + 1}] ${hit.title} (${hit.sourceType}): ${hit.snippet}"
        }.joinToString("\n")
        return NotebookQaResult(
            answer = "Grounded in ${hits.size} checked source(s):\n$body",
            citations = hits
        )
    }

    fun buildBriefingDoc(): String {
        val docs = ragStore.listAllDocuments().filter { it.selected }
        if (docs.isEmpty()) return "Select at least one local source to generate a Briefing Doc."
        val header = "EXECUTIVE BRIEFING (${docs.size} local sources)\n"
        val bullets = docs.joinToString("\n\n") { doc ->
            val preview = doc.content.trim().take(180)
            "• ${doc.title} [${doc.sourceType}]\n  $preview"
        }
        return header + bullets
    }

    fun buildStudyGuide(): String {
        val docs = ragStore.listAllDocuments().filter { it.selected }
        if (docs.isEmpty()) return "Select at least one local source to generate a Study Guide."
        return docs.mapIndexed { idx, doc ->
            "Q${idx + 1}: What are the key takeaways in ${doc.title}?\nA${idx + 1}: ${doc.content.trim().take(160)}"
        }.joinToString("\n\n")
    }

    fun buildAudioOverview(): List<AudioTurn> {
        val docs = ragStore.listAllDocuments().filter { it.selected }
        if (docs.isEmpty()) {
            return listOf(
                AudioTurn("Host A", "No local sources are checked right now. Check a document to generate an offline audio briefing.")
            )
        }
        val turns = mutableListOf(
            AudioTurn("Host A", "Welcome to your offline Aegis Notebook Audio Overview covering ${docs.size} local sources."),
            AudioTurn("Host B", "Everything here is synthesized locally on your device using Android TextToSpeech with zero cloud calls.")
        )
        docs.take(4).forEachIndexed { idx, doc ->
            val speaker = if (idx % 2 == 0) "Host A" else "Host B"
            val summary = doc.content.trim().take(160)
            turns.add(AudioTurn(speaker, "Looking at ${doc.title}: $summary"))
        }
        turns.add(AudioTurn("Host A", "That wraps your offline briefing. Tap any source or switch to Chat to inspect further."))
        return turns
    }
}
