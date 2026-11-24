package com.aibuilt.baadshahlens.data.db

import kotlinx.coroutines.flow.Flow
import java.io.File

class GalleryRepository(
    private val dao: GalleryDao,
    private val stillsDir: File
) {

    fun observeAll(): Flow<List<GalleryItem>> = dao.observeAll()

    fun observeOne(id: Long): Flow<GalleryItem?> = dao.observeOne(id)

    suspend fun byId(id: Long): GalleryItem? = dao.byId(id)

    suspend fun add(path: String, kind: MediaKind, generated: Boolean): Long =
        dao.insert(GalleryItem(path = path, kind = kind, createdAt = System.currentTimeMillis(), generated = generated))

    suspend fun delete(item: GalleryItem) {
        dao.delete(item.id)
        runCatching { File(item.path).delete() }
    }

    suspend fun deleteAll() {
        dao.all().forEach { runCatching { File(it.path).delete() } }
        dao.deleteAll()
    }

    suspend fun deleteGeneratedOlderThan(limitMillis: Long) {
        dao.all().filter { it.generated && it.createdAt < limitMillis }.forEach { delete(it) }
    }

    fun newStillFile(prefix: String = "still"): File {
        stillsDir.mkdirs()
        return File(stillsDir, "${prefix}_${System.currentTimeMillis()}.jpg")
    }
}
