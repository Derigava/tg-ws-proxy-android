import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}
val coreLock = rootProject.file("upstream/flowseal.lock.json")
val coreVersion = if (coreLock.exists()) (JsonSlurper().parse(coreLock) as Map<*, *>)["core_version"].toString().removePrefix("v") else "1.11.4"
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String) = System.getenv(env) ?: localProps.getProperty(prop)
val releaseKey = signingValue("TGWS_KEYSTORE_FILE", "KEYSTORE_FILE")

android {
    namespace = "com.amurcanov.tgwsproxy"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.derigava.tgwsproxy"
        minSdk = 24
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orElse("1000").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("1.0.0").get()
        buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")
        buildConfigField("boolean", "APP_UPDATES_ENABLED", providers.gradleProperty("appUpdatesEnabled").orElse("false").get())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    flavorDimensions.add("arch")
    productFlavors {
        create("arm64") {
            dimension = "arch"
            ndk.abiFilters.add("arm64-v8a")
        }
        create("emulator") {
            dimension = "arch"
            ndk.abiFilters.add("x86_64")
        }
    }
    signingConfigs {
        create("release") {
            if (releaseKey != null) {
                storeFile = file(releaseKey)
                storePassword = signingValue("TGWS_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("TGWS_KEY_ALIAS", "KEY_ALIAS")
                keyPassword = signingValue("TGWS_KEY_PASSWORD", "KEY_PASSWORD")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }
    // The archived Rust binaries are not part of the Python-based application.
    sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
    testOptions { unitTests.isReturnDefaultValues = true }
}
chaquopy {
    defaultConfig {
        version = "3.13"
        pip {
            install("httpx[http2]==0.28.1")
            install("certifi==2026.10.23")
        }
    }
    productFlavors {
        getByName("arm64") {
            pip { install("https://chaquo.com/pypi-13.1/cryptography/cryptography-42.0.8-1-cp313-cp313-android_24_arm64_v8a.whl") }
        }
        getByName("emulator") {
            pip { install("https://chaquo.com/pypi-13.1/cryptography/cryptography-42.0.8-1-cp313-cp313-android_24_x86_64.whl") }
        }
    }
    sourceSets {
        getByName("main") {
            srcDir("src/main/python")
            // Include just the unmodified proxy package, not upstream desktop UI/tests.
            srcDir(rootProject.file("upstream/flowseal"))
            exclude("tests/**", "LICENSE", "*.json", "*.md")
        }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.appcompat:appcompat:1.6.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
