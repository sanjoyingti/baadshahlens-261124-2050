package com.aibuilt.baadshahlens.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GalleryDao {

    @Query("SELECT * FROM gallery_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GalleryItem>>

    @Query("SELECT * FROM gallery_items WHERE id = :id")
    fun observeOne(id: Long): Flow<GalleryItem?>

    @Query("SELECT * FROM gallery_items WHERE id = :id")
    suspend fun byId(id: Long): GalleryItem?

    @Query("SELECT * FROM gallery_items ORDER BY createdAt ASC")
    suspend fun all(): List<GalleryItem>

    @Insert
    suspend fun insert(item: GalleryItem): Long

    @Query("DELETE FROM gallery_items WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM gallery_items")
    suspend fun deleteAll()
}
