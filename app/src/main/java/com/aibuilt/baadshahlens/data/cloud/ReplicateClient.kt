package com.aibuilt.baadshahlens.data.cloud

import android.content.Context
import android.util.Base64
import com.aibuilt.baadshahlens.data.settings.SettingsRepository
import com.aibuilt.baadshahlens.data.settings.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Token used for the three free demo shots when the person has not added their own. */
const val DEMO_TOKEN = "r8_demoBaadshahLensTrialToken0000000000"

sealed interface CloudResult {
    data class Success(val bytes: ByteArray) : CloudResult
    data class Failure(val message: String) : CloudResult
}

class ReplicateClient(
    private val tokenStore: TokenStore,
    private val settings: SettingsRepository,
    private val demoToken: String = DEMO_TOKEN
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun hasOwnToken(): Boolean = tokenStore.read().isNotBlank()

    private fun tokenFor(isDemo: Boolean): String =
        if (isDemo) demoToken else tokenStore.read()

    suspend fun testToken(token: String): CloudResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.replicate.com/v1/account")
                .header("Authorization", "Bearer $token")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    CloudResult.Success(byteArrayOf())
                } else {
                    CloudResult.Failure(
                        when (response.code) {
                            401 -> "That token is not valid. Copy it again from your Replicate account."
                            403 -> "That token is valid but not allowed to run models."
                            else -> "The service answered with code ${response.code}."
                        }
                    )
                }
            }
        }.getOrElse { CloudResult.Failure("No connection to the service: ${it.message ?: "unknown error"}") }
    }

    suspend fun inpaint(
        image: ByteArray,
        mask: ByteArray,
        prompt: String,
        stronger: Float,
        seed: Long,
        resolution: Int
    ): CloudResult = withContext(Dispatchers.IO) {
        val isDemo = !hasOwnToken()
        val token = tokenFor(isDemo)
        val model = settings.flow.let { "stability-ai/stable-diffusion-inpainting" }
        runCatching {
            val payload = buildString {
                append("{")
                append("\"input\":{")
                append("\"image\":\"data:image/jpeg;base64,").append(Base64.encodeToString(image, Base64.NO_WRAP)).append("\",")
                append("\"mask\":\"data:image/png;base64,").append(Base64.encodeToString(mask, Base64.NO_WRAP)).append("\",")
                append("\"prompt\":\"").append(prompt.replace("\"", "")).append("\",")
                append("\"strength\":").append(stronger.coerceIn(0.1f, 0.95f)).append(",")
                append("\"num_inference_steps\":24,")
                append("\"guidance_scale\":6.5,")
                append("\"seed\":").append(seed).append(",")
                append("\"width\":").append(resolution).append(",")
                append("\"height\":").append(resolution)
                append("}}")
            }
            val url = if (isDemo) {
                "https://api.replicate.com/v1/models/stability-ai/stable-diffusion-inpainting/predictions"
            } else {
                "https://api.replicate.com/v1/models/$model/predictions"
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val created = client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use CloudResult.Failure("The service refused the job (${response.code}). ${body.take(160)}")
                }
                json.parseToJsonElement(body).jsonObject
            }
            if (created is CloudResult) return@runCatching created

            val obj = created as JsonObject
            val pollUrl = obj["urls"]?.jsonObject?.get("get")?.jsonPrimitive?.content
                ?: return@runCatching CloudResult.Failure("The service did not return a job address.")

            var waited = 0
            while (waited < 180) {
                delay(2500)
                waited += 3
                val status = client.newCall(
                    Request.Builder().url(pollUrl).header("Authorization", "Bearer $token").build()
                ).execute().use { r -> json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }
                when (status["status"]?.jsonPrimitive?.content) {
                    "succeeded" -> {
                        val output = status["output"]
                        val outUrl = output?.let {
                            runCatching { it.jsonArray.first().jsonPrimitive.content }.getOrNull()
                                ?: runCatching { it.jsonPrimitive.content }.getOrNull()
                        } ?: return@runCatching CloudResult.Failure("The job finished without an image.")
                        val bytes = client.newCall(Request.Builder().url(outUrl).build()).execute().use { r ->
                            r.body?.bytes() ?: ByteArray(0)
                        }
                        if (bytes.isEmpty()) CloudResult.Failure("The finished image could not be downloaded.")
                        else CloudResult.Success(bytes)
                    }
                    "failed", "canceled" -> {
                        val err = status["error"]?.jsonPrimitive?.content ?: "unknown"
                        return@runCatching CloudResult.Failure("The job failed: $err")
                    }
                    else -> Unit
                }
            }
            CloudResult.Failure("The job took longer than three minutes.")
        }.getOrElse { CloudResult.Failure("Network problem: ${it.message ?: "unknown"}") }
    }
}
