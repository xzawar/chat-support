package com.codexce.supportchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, OutboxEntity::class],
    // 2: keepChat / createdAt / startedAt / userAgent / country on conversations.
    // 3: pending on messages, plus the outbox table backing the offline send queue.
    version = 3,
    exportSchema = false,
)
abstract class SupportDatabase : RoomDatabase() {

    abstract fun supportDao(): SupportDao

    companion object {
        private const val NAME = "support-chat.db"

        @Volatile
        private var instance: SupportDatabase? = null

        fun get(context: Context): SupportDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SupportDatabase::class.java,
                    NAME,
                )
                    // This is a cache of Firebase, never the origin of any data, so a schema
                    // change can safely drop it and re-sync rather than ship a migration.
                    // The one exception is the outbox, which is why an unsent message is
                    // flushed at the first opportunity rather than left sitting for days.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
