import java.util.Properties

plugins {
    id("com.android.application")
}

/**
 * Release signing, never stored in the repo. Taken from environment variables (GitHub Actions secrets),
 * or locally from ~/.flipfix/signing.properties if present. Without either, release builds are unsigned.
 *
 *   FLIPFIX_KEYSTORE, FLIPFIX_KEYSTORE_PASSWORD, FLIPFIX_KEY_ALIAS, FLIPFIX_KEY_PASSWORD
 */
val signing: Map<String, String> = run {
    val keys = listOf("FLIPFIX_KEYSTORE", "FLIPFIX_KEYSTORE_PASSWORD", "FLIPFIX_KEY_ALIAS", "FLIPFIX_KEY_PASSWORD")
    val fromEnv = keys.mapNotNull { k -> System.getenv(k)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
    if (fromEnv.size == keys.size) return@run fromEnv
    val local = File(System.getProperty("user.home"), ".flipfix/signing.properties")
    if (!local.exists()) return@run emptyMap()
    val p = Properties().apply { local.inputStream().use(::load) }
    keys.mapNotNull { k -> p.getProperty(k)?.let { k to it } }.toMap().takeIf { it.size == keys.size } ?: emptyMap()
}

android {
    namespace = "io.github.avengerdeveloper.flipfix"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.avengerdeveloper.flipfix"
        minSdk = 33
        targetSdk = 37
        // CI sets these from the build number and the release tag (v1.2.3 -> 1.2.3).
        versionCode = System.getenv("FLIPFIX_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("FLIPFIX_VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = file(signing.getValue("FLIPFIX_KEYSTORE"))
                storePassword = signing.getValue("FLIPFIX_KEYSTORE_PASSWORD")
                keyAlias = signing.getValue("FLIPFIX_KEY_ALIAS")
                keyPassword = signing.getValue("FLIPFIX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
