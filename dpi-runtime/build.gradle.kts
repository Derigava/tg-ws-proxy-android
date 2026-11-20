import java.io.File
import java.util.Properties

plugins {
    id("com.android.library")
}

// The native build needs both the NDK and CMake from the SDK. They are present
// on the Linux CI image but absent on the Windows workstation used for smoke
// tests, so the CMake build is registered only when the toolchain exists. This
// keeps `:app:testEmulatorDebugUnitTest` and JVM unit tests runnable without a
// system-level install, while Linux still packages libbyedpi.so for both ABIs.
fun sdkDir(): File? {
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        Properties().apply { localProps.inputStream().use { load(it) } }
            .getProperty("sdk.dir")?.let { return File(it) }
    }
    System.getenv("ANDROID_HOME")?.let { return File(it) }
    System.getenv("ANDROID_SDK_ROOT")?.let { return File(it) }
    return null
}

fun nativeSdkComponentsAvailable(): Boolean {
    val sdk = sdkDir() ?: return false
    val ndk = File(sdk, "ndk")
    val cmake = File(sdk, "cmake")
    return ndk.isDirectory && ndk.listFiles().orEmpty().any { it.isDirectory } &&
        cmake.isDirectory && cmake.listFiles().orEmpty().any { it.isDirectory }
}

android {
    namespace = "com.amurcanov.tgwsproxy.dpi"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86_64")
        }
    }

    if (nativeSdkComponentsAvailable()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
