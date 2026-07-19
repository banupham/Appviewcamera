import java.net.URI
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.banupham.appviewcamera.gateway"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.banupham.appviewcamera.gateway"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/mediamtx/jniLibs"))
    packaging {
        jniLibs.keepDebugSymbols += "**/libmediamtx.so"
    }
}

val mediaMtxVersion = "1.18.2"
val mediaMtxArchiveSha256 = "c78aa7a1bdab94b2b02be364661f17802143215dba37e1fa67c3e0849248b485"
val mediaMtxAmd64ArchiveSha256 = "73ed27c292e05ceb4990dcb34531f01872dfff5374b7515c45a202e0abf47706"
val mediaMtxOutput = layout.buildDirectory.file("generated/mediamtx/jniLibs/arm64-v8a/libmediamtx.so")
val mediaMtxX86Output = layout.buildDirectory.file("generated/mediamtx/jniLibs/x86_64/libmediamtx.so")

val prepareMediaMtx by tasks.registering {
    description = "Download and verify the ARM64 MediaMTX engine packaged in Android Gateway"
    outputs.file(mediaMtxOutput)
    doLast {
        val output = mediaMtxOutput.get().asFile
        output.parentFile.mkdirs()
        if (output.isFile && output.length() > 0) return@doLast

        val archive = layout.buildDirectory.file("downloads/mediamtx-v$mediaMtxVersion-linux-arm64.tar.gz").get().asFile
        archive.parentFile.mkdirs()
        if (!archive.isFile) {
            URI(
                "https://github.com/bluenviron/mediamtx/releases/download/v$mediaMtxVersion/" +
                    "mediamtx_v${mediaMtxVersion}_linux_arm64.tar.gz"
            ).toURL().openStream().use { input -> archive.outputStream().use(input::copyTo) }
        }
        val actualHash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actualHash == mediaMtxArchiveSha256) {
            "MediaMTX checksum mismatch: expected $mediaMtxArchiveSha256, got $actualHash"
        }

        GZIPInputStream(archive.inputStream()).use { tar ->
            val header = ByteArray(512)
            var extracted = false
            while (tar.readNBytes(header, 0, header.size) == header.size) {
                if (header.all { it == 0.toByte() }) break
                val name = header.copyOfRange(0, 100).takeWhile { it != 0.toByte() }
                    .toByteArray().toString(Charsets.UTF_8)
                val sizeText = header.copyOfRange(124, 136).takeWhile { it != 0.toByte() && it != ' '.code.toByte() }
                    .toByteArray().toString(Charsets.US_ASCII).trim()
                val size = sizeText.ifBlank { "0" }.toLong(8)
                if (name == "mediamtx") {
                    output.outputStream().use { target ->
                        var remaining = size
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(read > 0) { "MediaMTX archive ended early" }
                            target.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    extracted = true
                } else {
                    var remaining = size
                    while (remaining > 0) {
                        val skipped = tar.skip(remaining)
                        check(skipped > 0) { "MediaMTX archive ended early" }
                        remaining -= skipped
                    }
                }
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) tar.skipNBytes(padding)
                if (extracted) break
            }
            check(extracted && output.length() > 0) { "MediaMTX binary not found in archive" }
        }
    }
}


val prepareMediaMtxX86 by tasks.registering {
    description = "Download and verify the x86_64 MediaMTX engine used by Android emulators"
    outputs.file(mediaMtxX86Output)
    doLast {
        val output = mediaMtxX86Output.get().asFile
        output.parentFile.mkdirs()
        if (output.isFile && output.length() > 0) return@doLast

        val archive = layout.buildDirectory.file("downloads/mediamtx-v$mediaMtxVersion-linux-amd64.tar.gz").get().asFile
        archive.parentFile.mkdirs()
        if (!archive.isFile) {
            URI(
                "https://github.com/bluenviron/mediamtx/releases/download/v$mediaMtxVersion/" +
                    "mediamtx_v${mediaMtxVersion}_linux_amd64.tar.gz"
            ).toURL().openStream().use { input -> archive.outputStream().use(input::copyTo) }
        }
        val actualHash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actualHash == mediaMtxAmd64ArchiveSha256) {
            "MediaMTX x86_64 checksum mismatch: expected $mediaMtxAmd64ArchiveSha256, got $actualHash"
        }

        GZIPInputStream(archive.inputStream()).use { tar ->
            val header = ByteArray(512)
            var extracted = false
            while (tar.readNBytes(header, 0, header.size) == header.size) {
                if (header.all { it == 0.toByte() }) break
                val name = header.copyOfRange(0, 100).takeWhile { it != 0.toByte() }
                    .toByteArray().toString(Charsets.UTF_8)
                val sizeText = header.copyOfRange(124, 136).takeWhile { it != 0.toByte() && it != ' '.code.toByte() }
                    .toByteArray().toString(Charsets.US_ASCII).trim()
                val size = sizeText.ifBlank { "0" }.toLong(8)
                if (name == "mediamtx") {
                    output.outputStream().use { target ->
                        var remaining = size
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            check(read > 0) { "MediaMTX x86_64 archive ended early" }
                            target.write(buffer, 0, read)
                            remaining -= read
                        }
                    }
                    extracted = true
                } else {
                    var remaining = size
                    while (remaining > 0) {
                        val skipped = tar.skip(remaining)
                        check(skipped > 0) { "MediaMTX x86_64 archive ended early" }
                        remaining -= skipped
                    }
                }
                val padding = (512 - (size % 512)) % 512
                if (padding > 0) tar.skipNBytes(padding)
                if (extracted) break
            }
            check(extracted && output.length() > 0) { "MediaMTX x86_64 binary not found in archive" }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareMediaMtx, prepareMediaMtxX86)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303")
}
