package com.aibuilt.baadshahlens.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "baadshah_settings")

enum class QualityTier(val label: String) {
    FAST("Fast"), BALANCED("Balanced"), BEST("Best");

    companion object {
        fun fromName(name: String?): QualityTier =
            entries.firstOrNull { it.name == name } ?: FAST
    }
}

enum class Gender { MALE, FEMALE }

enum class OutputResolution(val label: String, val pixels: Int) {
    P1024("1024 px", 1024), P1536("1536 px", 1536);

    companion object {
        fun fromName(name: String?): OutputResolution =
            entries.firstOrNull { it.name == name } ?: P1024
    }
}

data class AppSettings(
    val tier: QualityTier = QualityTier.FAST,
    val strengthFast: Float = 0.85f,
    val strengthBalanced: Float = 0.90f,
    val strengthBest: Float = 0.95f,
    val genderDefault: Gender = Gender.MALE,
    val genderInferred: Gender? = null,
    val cleanGlassesRecording: Boolean = false,
    val autoDeleteGenerated: Boolean = true,
    val keepOriginalsOffDevice: Boolean = true,
    val landscape: Boolean = false,
    val legalAccepted: Boolean = false,
    val framingGuideShown: Int = 0,
    val glassesHintShown: Boolean = false,
    val demoShotsUsed: Int = 0,
    val cloudModel: String = "stability-ai/stable-diffusion-inpainting",
    val resolution: OutputResolution = OutputResolution.P1024,
    val onDeviceOnly: Boolean = false
) {
    fun strengthFor(tier: QualityTier): Float = when (tier) {
        QualityTier.FAST -> strengthFast
        QualityTier.BALANCED -> strengthBalanced
        QualityTier.BEST -> strengthBest
    }

    val effectiveTier: QualityTier get() = if (onDeviceOnly) {
        if (tier == QualityTier.BEST) QualityTier.BALANCED else tier
    } else {
        tier
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val tier = stringPreferencesKey("tier")
        val strengthFast = intPreferencesKey("strength_fast")
        val strengthBalanced = intPreferencesKey("strength_balanced")
        val strengthBest = intPreferencesKey("strength_best")
        val genderDefault = stringPreferencesKey("gender_default")
        val genderInferred = stringPreferencesKey("gender_inferred")
        val cleanGlasses = booleanPreferencesKey("clean_glasses")
        val autoDelete = booleanPreferencesKey("auto_delete")
        val keepOriginals = booleanPreferencesKey("keep_originals")
        val landscape = booleanPreferencesKey("landscape")
        val legal = booleanPreferencesKey("legal")
        val framing = intPreferencesKey("framing_shown")
        val glassesHint = booleanPreferencesKey("glasses_hint")
        val demoShots = intPreferencesKey("demo_shots")
        val cloudModel = stringPreferencesKey("cloud_model")
        val resolution = stringPreferencesKey("resolution")
        val onDeviceOnly = booleanPreferencesKey("on_device_only")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            tier = QualityTier.fromName(p[Keys.tier]),
            strengthFast = (p[Keys.strengthFast] ?: 85) / 100f,
            strengthBalanced = (p[Keys.strengthBalanced] ?: 90) / 100f,
            strengthBest = (p[Keys.strengthBest] ?: 95) / 100f,
            genderDefault = p[Keys.genderDefault]?.let { Gender.valueOf(it) } ?: Gender.MALE,
            genderInferred = p[Keys.genderInferred]?.let { Gender.valueOf(it) },
            cleanGlassesRecording = p[Keys.cleanGlasses] ?: false,
            autoDeleteGenerated = p[Keys.autoDelete] ?: true,
            keepOriginalsOffDevice = p[Keys.keepOriginals] ?: true,
            landscape = p[Keys.landscape] ?: false,
            legalAccepted = p[Keys.legal] ?: false,
            framingGuideShown = p[Keys.framing] ?: 0,
            glassesHintShown = p[Keys.glassesHint] ?: false,
            demoShotsUsed = p[Keys.demoShots] ?: 0,
            cloudModel = p[Keys.cloudModel] ?: "stability-ai/stable-diffusion-inpainting",
            resolution = OutputResolution.fromName(p[Keys.resolution]),
            onDeviceOnly = p[Keys.onDeviceOnly] ?: false
        )
    }

    suspend fun setTier(tier: QualityTier) = put { it[Keys.tier] = tier.name }

    suspend fun setStrength(tier: QualityTier, value: Float) = put {
        val percent = (value * 100).toInt().coerceIn(10, 100)
        when (tier) {
            QualityTier.FAST -> it[Keys.strengthFast] = percent
            QualityTier.BALANCED -> it[Keys.strengthBalanced] = percent
            QualityTier.BEST -> it[Keys.strengthBest] = percent
        }
    }

    suspend fun setGenderDefault(gender: Gender) = put { it[Keys.genderDefault] = gender.name }

    suspend fun setGenderInferred(gender: Gender?) = put {
        if (gender == null) it.remove(Keys.genderInferred) else it[Keys.genderInferred] = gender.name
    }

    suspend fun setCleanGlasses(value: Boolean) = put { it[Keys.cleanGlasses] = value }
    suspend fun setAutoDelete(value: Boolean) = put { it[Keys.autoDelete] = value }
    suspend fun setKeepOriginals(value: Boolean) = put { it[Keys.keepOriginals] = value }
    suspend fun setLandscape(value: Boolean) = put { it[Keys.landscape] = value }
    suspend fun setLegalAccepted() = put { it[Keys.legal] = true }
    suspend fun bumpFramingGuide() = put { it[Keys.framing] = (it[Keys.framing] ?: 0) + 1 }
    suspend fun setGlassesHintShown() = put { it[Keys.glassesHint] = true }
    suspend fun bumpDemoShots() = put { it[Keys.demoShots] = (it[Keys.demoShots] ?: 0) + 1 }
    suspend fun setCloudModel(model: String) = put { it[Keys.cloudModel] = model }
    suspend fun setResolution(res: OutputResolution) = put { it[Keys.resolution] = res.name }
    suspend fun setOnDeviceOnly(value: Boolean) = put { it[Keys.onDeviceOnly] = value }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }
}
