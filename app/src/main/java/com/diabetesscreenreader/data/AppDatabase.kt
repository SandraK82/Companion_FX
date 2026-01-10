package com.diabetesscreenreader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [GlucoseReading::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun glucoseDao(): GlucoseDao

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
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
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
