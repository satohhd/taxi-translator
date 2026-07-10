import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// PoC限定: APIキーを local.properties(anthropicApiKey=sk-ant-...)から埋め込む。
// 本番では中継サーバー経由に変更すること(PoC実装計画.md 7章参照)。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val anthropicApiKey: String =
    localProps.getProperty("anthropicApiKey") ?: System.getenv("ANTHROPIC_API_KEY") ?: ""

android {
    namespace = "jp.asystem.taxitranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.asystem.taxitranslator"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "0.6.2-poc"
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
        buildConfigField("String", "CLAUDE_MODEL", "\"claude-haiku-4-5\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/versions/**",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.translate)
    implementation(libs.anthropic.java)
    implementation(libs.play.services.nearby)
}
