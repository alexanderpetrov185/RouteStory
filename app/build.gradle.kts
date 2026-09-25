import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.alexander.routestory"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alexander.routestory"
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "0.0.3"


        // Optional free CARTO basemap key (dark live tiles). Set CARTO_API_KEY=... in
        // local.properties; when absent the live map falls back to plain OSM raster.
        val localProps = Properties()
        val localFile = rootProject.file("local.properties")
        if (localFile.exists()) localProps.load(localFile.inputStream())
        val cartoKey = localProps.getProperty("CARTO_API_KEY") ?: ""
        buildConfigField("String", "CARTO_API_KEY", "\"$cartoKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    // MyLocation "navigator arrow" icon for the map locate button.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.play.services.location)
    implementation(libs.androidx.exifinterface)
    implementation(libs.billing.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.maplibre.android.sdk)
    // Used to inject the OSM-compliant User-Agent into MapLibre tile requests.
    implementation(libs.okhttp)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
