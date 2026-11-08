plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.clonehero.pad"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.clonehero.pad"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // klien ADB (pairing Debug nirkabel + koneksi TLS) – TLS lewat BouncyCastle
    implementation("com.github.osservatorionessuno:libadb-android-bc:3.2.0")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.84")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.84")
}
