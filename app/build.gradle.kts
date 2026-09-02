import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/*
 * Release signing credentials live outside the repository, by default in ~/.mydrop-signing/. The
 * keystore is the one thing here that cannot be regenerated: Android identifies an app by its
 * signing key, so losing it means friends can never update in place — they would have to
 * uninstall first, losing their servers and settings. Keep a backup somewhere that is not this
 * machine.
 *
 * The location is overridable because the default is a guess about the machine rather than about
 * the project. Point it somewhere else with either:
 *
 *     ./gradlew :app:assembleRelease -Pyumi.signingDir=E:/.mydrop-signing
 *     YUMI_SIGNING_DIR=E:/.mydrop-signing ./gradlew :app:assembleRelease
 *
 * A missing file is not an error; it just leaves the release build unsigned, so the project still
 * builds anywhere else.
 */
val signingDir: File = providers.gradleProperty("yumi.signingDir")
    .orElse(providers.environmentVariable("YUMI_SIGNING_DIR"))
    .map(::File)
    .getOrElse(File(System.getProperty("user.home"), ".mydrop-signing"))

val signingProperties: Properties? = File(signingDir, "keystore.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.mydrop.vpn"
    // Compose 1.12 / material3 1.5 publish AAR metadata demanding API 37 to compile against.
    // targetSdk stays at 36 on purpose: opting into new runtime behaviour is a separate,
    // deliberate step from compiling against newer APIs.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mydrop.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 21
        versionName = "1.0.6"
        // ABI selection lives in `splits.abi` below; the two cannot both be set.
    }

    signingConfigs {
        if (signingProperties != null) {
            create("release") {
                // `storeFile` is recorded as an absolute path by whichever machine created it,
                // so it points at that machine and nowhere else. When it is not there, the
                // keystore sitting beside keystore.properties is the one that was meant — which
                // is what makes the credentials directory movable at all.
                storeFile = File(signingProperties.getProperty("storeFile")).let { recorded ->
                    if (recorded.isFile) recorded else File(signingDir, recorded.name)
                }
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                // AGP drops v1 by itself here: minSdk 26 is above the API 24 where v2 landed, so
                // the JAR-signing scheme would only add weight and install time.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // One APK per architecture. libbox's native library dominates the download, so a universal
    // APK would make every user carry a second copy of the core they cannot run.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
        )
    }
}

dependencies {
    // sing-box core, built from source with the SagerNet gomobile fork.
    // See README for the exact build command; the AAR is not fetchable from any Maven repo.
    implementation(files("libs/libbox.aar"))

    implementation(platform(libs.compose.bom))

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.graphics.shapes)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
