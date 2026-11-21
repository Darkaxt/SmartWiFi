import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appId = providers.gradleProperty("SMARTWIFI_APP_ID").get()
val appVersionCode = providers.gradleProperty("SMARTWIFI_VERSION_CODE").get().toInt()
val appVersionName = providers.gradleProperty("SMARTWIFI_VERSION_NAME").get()

// Signing comes from SMARTWIFI_* environment variables (GitHub Actions secrets) or a
// private, git-ignored signing.properties file. Release builds refuse any other key.
val signingProperties = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(prop)?.takeIf { it.isNotBlank() }

val storeFilePath = signingValue("SMARTWIFI_STORE_FILE", "storeFile")
val storePasswordValue = signingValue("SMARTWIFI_STORE_PASSWORD", "storePassword")
val keyAliasValue = signingValue("SMARTWIFI_KEY_ALIAS", "keyAlias")
val keyPasswordValue = signingValue("SMARTWIFI_KEY_PASSWORD", "keyPassword")
val signingValues = listOf(storeFilePath, storePasswordValue, keyAliasValue, keyPasswordValue)
val hasSigning = signingValues.all { !it.isNullOrBlank() }
if (signingValues.any { !it.isNullOrBlank() } && !hasSigning) {
    throw GradleException("Release signing is only partially configured.")
}

val expectedSigningSha256 = providers.gradleProperty("SMARTWIFI_SIGNING_SHA256").orNull
    ?.lowercase()?.replace(":", "")?.trim()?.takeIf { it.isNotEmpty() }

val verifyReleaseSigningCertificate = tasks.register("verifyReleaseSigningCertificate") {
    group = "verification"
    description = "Refuses release builds unless the configured key matches the pinned certificate."
    doLast {
        if (!hasSigning) throw GradleException("Release builds require the permanent SmartWiFi key.")
        val expected = expectedSigningSha256
            ?: throw GradleException("SMARTWIFI_SIGNING_SHA256 is not set in gradle.properties.")
        val store = rootProject.file(storeFilePath!!)
        val keyStore = KeyStore.getInstance("PKCS12")
        store.inputStream().use { keyStore.load(it, storePasswordValue!!.toCharArray()) }
        val cert = keyStore.getCertificate(keyAliasValue)
            ?: throw GradleException("Alias '$keyAliasValue' not found in keystore.")
        val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            throw GradleException("Signing certificate mismatch: expected $expected, got $actual.")
        }
    }
}

android {
    namespace = appId
    compileSdk = 37

    defaultConfig {
        applicationId = appId
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasSigning) {
            create("stable") {
                storeFile = rootProject.file(storeFilePath!!)
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("stable")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigningCertificate)
}

dependencies {
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")

    testImplementation("junit:junit:4.13.2")
}
