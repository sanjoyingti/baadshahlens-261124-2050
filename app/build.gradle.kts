        plugins {
            id("com.android.application")
            id("org.jetbrains.kotlin.plugin.compose")
            id("org.jetbrains.kotlin.plugin.serialization")
            id("com.google.devtools.ksp")
        }

        android {
            namespace = "com.aibuilt.baadshahlens"
            compileSdk = 37
            defaultConfig {
                applicationId = "com.aibuilt.baadshahlens"
                minSdk = 26
                targetSdk = 37
                versionCode = 1
                versionName = "1.0"
            }
            signingConfigs {
                // Created once by the workflow and kept in the repo, so every build installs over the last one.
                getByName("debug") {
                    if (file("signing.keystore").exists()) {
                        storeFile = file("signing.keystore")
                        storePassword = "android"
                        keyAlias = "app"
                        keyPassword = "android"
                    }
                }
            }
            buildTypes { release { isMinifyEnabled = false } }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            buildFeatures { compose = true }
        }

        kotlin {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }

        dependencies {
            val composeBom = platform("androidx.compose:compose-bom:2026.11.01")
            implementation(composeBom)
            implementation("androidx.core:core-ktx:1.19.1")
            implementation("androidx.activity:activity-compose:1.13.0")
            implementation("androidx.compose.ui:ui")
            implementation("androidx.compose.ui:ui-tooling-preview")
            implementation("androidx.compose.foundation:foundation")
            implementation("androidx.compose.material3:material3")
            implementation("androidx.compose.material:material-icons-extended")
            implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.1")
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.1")
            implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.1")
            implementation("androidx.navigation:navigation-compose:2.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
            implementation("androidx.camera:camera-core:1.4.2")
implementation("androidx.camera:camera-camera2:1.4.2")
implementation("androidx.camera:camera-lifecycle:1.4.2")
implementation("androidx.camera:camera-view:1.4.2")
implementation("androidx.camera:camera-video:1.4.2")
implementation("androidx.camera:camera-effects:1.4.2")
implementation("com.google.mediapipe:tasks-vision:0.10.21")
implementation("androidx.room:room-ktx:2.8.5")
implementation("androidx.room:room-runtime:2.8.5")
implementation("androidx.datastore:datastore-preferences:1.3.0")
ksp("androidx.room:room-compiler:2.8.5")
        }