package com.hermes.mobile.data.local

import android.content.Context
import androidx.room.*
import com.hermes.mobile.data.model.Message
import com.hermes.mobile.data.model.Session
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun getAllSessions(): Flow<List<Session>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: Session)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("UPDATE sessions SET title = :title WHERE id = :sessionId")
    suspend fun renameSession(sessionId: String, title: String)

    @Query("UPDATE sessions SET archived = :archived, updatedAt = :ts WHERE id = :sessionId")
    suspend fun setArchived(sessionId: String, archived: Boolean, ts: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET messageCount = messageCount + 1, updatedAt = :timestamp WHERE id = :sessionId")
    suspend fun incrementMessageCount(sessionId: String, timestamp: Long = System.currentTimeMillis())

    /** Latest active session — bottom-bar tabs resume this instead of
     *  silently creating a new one. */
    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): com.hermes.mobile.data.model.Session?

    @Query("SELECT * FROM sessions WHERE isActive = 1 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLastSession(): Session?
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessages(sessionId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(sessionId: String): List<Message>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long

    @Query("UPDATE messages SET content = :content, isStreaming = :isStreaming WHERE id = :messageId")
    suspend fun updateMessage(messageId: Long, content: String, isStreaming: Boolean = false)

    @Query(
        "UPDATE messages SET content = :content, isStreaming = :isStreaming, " +
            "attachmentUrl = :attachmentUrl, attachmentType = :attachmentType, " +
            "attachmentName = :attachmentName WHERE id = :messageId"
    )
    suspend fun updateMessageWithAttachment(
        messageId: Long,
        content: String,
        isStreaming: Boolean,
        attachmentUrl: String,
        attachmentType: String,
        attachmentName: String?
    )

    @Query("UPDATE messages SET tokens = :tokens WHERE id = :messageId")
    suspend fun updateMessageTokens(messageId: Long, tokens: Long)

    @Query("UPDATE messages SET toolActivity = :activity WHERE id = :messageId")
    suspend fun updateMessageToolActivity(messageId: Long, activity: String)

    @Query("UPDATE messages SET contextTokens = :tokens WHERE id = :messageId")
    suspend fun updateMessageContextTokens(messageId: Long, tokens: Long)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: Long, status: com.hermes.mobile.data.model.MessageStatus)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND isStreaming = 1")
    suspend fun deleteStreamingPlaceholders(sessionId: String)

    @Query("UPDATE messages SET reaction = :reaction WHERE id = :messageId")
    suspend fun updateReaction(messageId: Long, reaction: String?)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteSessionMessages(sessionId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Query("UPDATE messages SET content = :content, editedAt = :editedAt WHERE id = :messageId")
    suspend fun updateMessageEdit(messageId: Long, content: String, editedAt: Long)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: Long): Message?

    @Query("""
        UPDATE messages SET content = :content, isStreaming = 0 
        WHERE id = (
            SELECT id FROM messages 
            WHERE sessionId = :sessionId AND role = 'ASSISTANT' AND isStreaming = 1 
            ORDER BY timestamp DESC LIMIT 1
        )
    """)
    suspend fun updateLastStreamingMessage(sessionId: String, content: String)

    // Process-death cleanup: streaming placeholders left as isStreaming=1
    // when the app died mid-stream would otherwise render the NEXT stream's
    // live text into a stale bubble too (two bubbles, same text).
    @Query("UPDATE messages SET isStreaming = 0 WHERE sessionId = :sessionId AND isStreaming = 1")
    suspend fun finalizeStaleStreaming(sessionId: String)
}

@Database(
    entities = [Message::class, Session::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        /** v1 → v2: replyToText (quote chip) + reaction (👍) columns. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN replyToText TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN reaction TEXT")
            }
        }

        /** v2 → v3: Telegram-style delivery status tick column. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN status TEXT")
            }
        }

        /** v3 → v4: editedAt column for edited message indicator. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN editedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v4 → v5: tokens column for usage stats. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN tokens INTEGER NOT NULL DEFAULT 0")
            }
        }
        /** v5 -> v6: persisted tool activity lines on assistant messages. */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolActivity TEXT")
            }
        }

        /** v6 -> v7: per-turn context fill (prompt tokens) on assistant rows. */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN contextTokens INTEGER NOT NULL DEFAULT 0")
            }
        }
        /** v7 -> v8: Telegram-style session archive flag. */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "hermes_mobile.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                // NO fallbackToDestructiveMigration: a missed migration must
                // crash LOUDLY at open (caught upstream) rather than silently
                // wipe every session and message the user ever had.
                .build()
        }
    }
}
