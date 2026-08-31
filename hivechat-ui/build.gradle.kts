plugins {
    // AGP 9 has built-in Kotlin support — no kotlin.android plugin required.
    alias(libs.plugins.android.library)
    `maven-publish`
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hivehd.chat.ui"
    compileSdk = 37

    defaultConfig {
        /* Compose requires 21; 24 keeps it aligned with the core module. */
        minSdk = 24
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api(project(":hivechat"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
}

/* JitPack publishing.
   Without an explicit publication JitPack injects its own, and it injects
   GROOVY — `singleVariant('release')` — into a Kotlin DSL build file, where
   single quotes are a character literal. The build then dies with "Too many
   characters in a character literal" before it compiles a line of Kotlin.
   Declaring the publication here leaves it nothing to inject.

   groupId/version come from the -Pgroup / -Pversion JitPack passes on the
   command line, with local fallbacks so `publishToMavenLocal` works on a
   developer machine too. */
publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
            groupId = "com.github.maciuchx.hive-chat-sdk-kotlin"
            artifactId = project.name
            version = project.version.toString()
                .takeIf { it != Project.DEFAULT_VERSION } ?: "0.1.1"
        }
    }
}
