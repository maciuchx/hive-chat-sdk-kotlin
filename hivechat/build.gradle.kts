plugins {
    // AGP 9 has built-in Kotlin support — no kotlin.android plugin required.
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.hivehd.chat"
    compileSdk = 37

    defaultConfig {
        /* API 24 covers effectively every device still receiving app updates,
           and nothing here needs more. The UI module asks for more only
           because Compose does. */
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    /* OkHttp is the only third-party dependency, and it is unavoidable:
       Android ships no WebSocket client below API 33, and the Socket.IO
       protocol needs one. Almost every app already has it. */
    api(libs.okhttp)

    testImplementation(libs.junit)
    /* android.jar's org.json is stubbed out in unit tests, so the real
       implementation is added for the test classpath only. */
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
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
