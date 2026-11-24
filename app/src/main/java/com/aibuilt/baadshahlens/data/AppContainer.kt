package com.aibuilt.baadshahlens.data

import android.content.Context
import androidx.room.Room
import com.aibuilt.baadshahlens.BaadshahApp
import com.aibuilt.baadshahlens.data.cloud.ReplicateClient
import com.aibuilt.baadshahlens.data.db.AppDatabase
import com.aibuilt.baadshahlens.data.db.GalleryRepository
import com.aibuilt.baadshahlens.data.models.ModelRepository
import com.aibuilt.baadshahlens.data.settings.SettingsRepository
import com.aibuilt.baadshahlens.data.settings.TokenStore
import com.aibuilt.baadshahlens.vision.VisionPipeline
import java.io.File

class AppContainer(context: Context) {

    private val app = context.applicationContext

    val context: Context get() = app

    val settings = SettingsRepository(app)

    val tokenStore = TokenStore(app)

    val database: AppDatabase = Room.databaseBuilder(
        app,
        AppDatabase::class.java,
        "baadshah-lens.db"
    ).fallbackToDestructiveMigration().build()

    val gallery = GalleryRepository(database.galleryDao(), File(app.filesDir, "stills"))

    val models = ModelRepository(app)

    val cloud = ReplicateClient(tokenStore, settings)

    val vision: VisionPipeline by lazy { VisionPipeline() }

    val stillsDir: File get() = File(app.filesDir, "stills").also { it.mkdirs() }
    val cloudDir: File get() = File(app.filesDir, "cloud").also { it.mkdirs() }
    val videosDir: File get() = File(app.filesDir, "videos").also { it.mkdirs() }
}

fun Context.appContainer(): AppContainer = (applicationContext as BaadshahApp).container
