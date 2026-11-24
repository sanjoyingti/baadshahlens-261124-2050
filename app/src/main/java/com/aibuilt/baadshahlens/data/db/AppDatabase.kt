package com.aibuilt.baadshahlens.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [GalleryItem::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun galleryDao(): GalleryDao
}
