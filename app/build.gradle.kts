plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

val versionCodeFromCi = (System.getenv("METTLE_VERSION_CODE") ?: "1").toInt()

android {
    namespace = "app.mettle"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mettle"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeFromCi
        versionName = "0.1.$versionCodeFromCi"
        // Google Maps key comes from the METTLE_MAPS_API_KEY GitHub secret (never committed).
        manifestPlaceholders["mapsApiKey"] = System.getenv("METTLE_MAPS_API_KEY") ?: ""
        // Public project values (designed to ship inside the app; row-level security protects data).
        buildConfigField("String", "SUPABASE_URL", "\"${project.property("mettle.supabaseUrl")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${project.property("mettle.supabasePublishableKey")}\"")
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("METTLE_KEYSTORE_PATH")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("METTLE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("METTLE_KEY_ALIAS")
                keyPassword = System.getenv("METTLE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("METTLE_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "3g"
                // Screenshot tests: use a pre-downloaded Android runtime when present (local machine only).
                val deps = File(System.getProperty("user.home"), ".robolectric-deps")
                if (deps.isDirectory) {
                    it.systemProperty("robolectric.offline", "true")
                    it.systemProperty("robolectric.dependency.dir", deps.absolutePath)
                }
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.play.services.location)
    implementation(libs.androidx.work.runtime)
    implementation(libs.multiplatform.settings)
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.functions)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.concurrent.futures)
    implementation("com.google.guava:guava:33.3.1-android")
    implementation(libs.health.connect)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
