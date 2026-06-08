package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.model.LayerEntity
import com.example.data.model.ProjectEntity

@Database(entities = [ProjectEntity::class, LayerEntity::class], version = 1, exportSchema = false)
abstract class PhotoXDatabase : RoomDatabase() {
    abstract fun dao(): PhotoXDao

    companion object {
        @Volatile
        private var INSTANCE: PhotoXDatabase? = null

        fun getDatabase(context: Context): PhotoXDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PhotoXDatabase::class.java,
                    "photox_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
