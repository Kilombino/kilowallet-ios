plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(25)
    jvm()
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            api(libs.secp256k1)
            implementation(libs.sha2)
            implementation(libs.hmac.sha2)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(libs.secp256k1.jni.jvm)
        }
        jvmTest.dependencies {
            // JoinBlaket's tests read the taker vectors as Android's tests do (org.json, JUnit 4 asserts).
            implementation("org.json:json:20240303")
            implementation("junit:junit:4.13.2")
        }
    }
}

// A failing test says why in the CI log, not only in the HTML report nobody downloads.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
