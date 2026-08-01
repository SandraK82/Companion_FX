package com.diabetesscreenreader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        GlucoseReading::class,
        CompanionEventEntity::class,
        CompanionObservationStateEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun glucoseDao(): GlucoseDao
    abstract fun companionEventDao(): CompanionEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "diabetes_screenreader_db"
                )
                    // Never erase readings or the event ledger during an APK
                    // update.  Every schema change must have an explicit
                    // migration so a sleeping companion phone can be updated
                    // without losing its upload queue.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS companion_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        eventTimestamp INTEGER NOT NULL,
                        firstSeenAt INTEGER NOT NULL,
                        amount REAL,
                        carbs REAL,
                        rate REAL,
                        durationMinutes INTEGER,
                        notes TEXT,
                        state TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        lastAttemptAt INTEGER,
                        serverId TEXT,
                        lastError TEXT,
                        reconciliationState TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_companion_events_fingerprint ON companion_events(fingerprint)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_companion_events_state_eventTimestamp ON companion_events(state, eventTimestamp)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS companion_observation_state (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        value TEXT NOT NULL,
                        observedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE glucose_readings ADD COLUMN uploaderBattery INTEGER")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE companion_events ADD COLUMN source TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE companion_events ADD COLUMN observedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE companion_events ADD COLUMN confidence REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE companion_events ADD COLUMN correlationKey TEXT")
                db.execSQL(
                    "ALTER TABLE companion_events ADD COLUMN confirmationState TEXT NOT NULL DEFAULT 'not_required'"
                )
                db.execSQL("ALTER TABLE companion_events ADD COLUMN uploadAllowed INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE companion_events ADD COLUMN isBaseline INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE companion_events SET observedAt = firstSeenAt WHERE observedAt = 0")
            }
        }

        /**
         * The first v4 build briefly created an index that is not part of the
         * Room entity definition.  Drop it explicitly so phones that tried
         * that build can continue without a destructive database reset.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_companion_events_type_eventTimestamp")
            }
        }
    }
}

class Converters {
    @androidx.room.TypeConverter
    fun fromGlucoseUnit(value: GlucoseUnit): String = value.name

    @androidx.room.TypeConverter
    fun toGlucoseUnit(value: String): GlucoseUnit = GlucoseUnit.valueOf(value)

    @androidx.room.TypeConverter
    fun fromGlucoseTrend(value: GlucoseTrend): String = value.name

    @androidx.room.TypeConverter
    fun toGlucoseTrend(value: String): GlucoseTrend = GlucoseTrend.valueOf(value)
}
