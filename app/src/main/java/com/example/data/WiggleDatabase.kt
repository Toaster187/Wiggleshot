package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WiggleCapture::class], version = 3, exportSchema = false)
abstract class WiggleDatabase : RoomDatabase() {
    abstract fun wiggleDao(): WiggleDao

    companion object {
        @Volatile
        private var INSTANCE: WiggleDatabase? = null

        fun getDatabase(context: Context): WiggleDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WiggleDatabase::class.java,
                    "wiggle_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
