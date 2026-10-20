import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.ZoneId
import java.util.Properties
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ronald.rimmodupdater"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ronald.rimmodupdater"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        val buildTime = ZonedDateTime.now(ZoneId.of("Asia/Jakarta"))
            .format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm"))
        buildConfigField("String", "BUILD_TIME", "\"$buildTime WIB\"")
    }

    // Kunci tanda tangan tetap, supaya build baru bisa meng-update aplikasi yang sudah terpasang.
    val signProps = Properties().apply {
        rootProject.file("signing/keystore.properties").inputStream().use { load(it) }
    }
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing/release.jks")
            storePassword = signProps.getProperty("storePassword")
            keyAlias = signProps.getProperty("keyAlias")
            keyPassword = signProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/9/module-info.class",
                "module-info.class",
            )
            pickFirsts += setOf("META-INF/*.kotlin_module")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Steam (login + download Workshop)
    implementation("in.dragonbra:javasteam:1.8.0")
    implementation("in.dragonbra:javasteam-depotdownloader:1.8.0")
    implementation("com.google.protobuf:protobuf-java:4.31.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("org.tukaani:xz:1.11")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
}
