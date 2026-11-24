package com.aibuilt.baadshahlens.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MediaKind { STILL, VIDEO }

@Entity(tableName = "gallery_items")
data class GalleryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val kind: MediaKind,
    val createdAt: Long,
    val generated: Boolean
)
