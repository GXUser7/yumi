import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mydrop.vpn.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        // Правила уезжают в оба приложения вместе с модулем, а не переписываются в каждом
        // руками. У :tv их было две строки из нужных, и релизная сборка телевизора
        // собиралась, подписывалась и падала бы уже на устройстве.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    api(files("libs/libyumi.aar"))
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
