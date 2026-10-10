import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(25)
    // The JVM target only exists to compile and preview the shared UI on a desktop (no Mac needed).
    jvm("desktop")

    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "KilowalletApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.coroutines.swing)
            }
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.kilombino.kilowallet.app.res"
    generateResClass = always
}

compose.desktop {
    application {
        mainClass = "com.kilombino.kilowallet.app.DesktopMainKt"
        (findProperty("kwDemo") as String?)?.let { jvmArgs += "-Dkw.demo=$it" }
    }
}

// Developer helper: the desktop preview's classpath, to run it directly with `java -cp`.
tasks.register("printDesktopClasspath") {
    val cp = kotlin.jvm("desktop").compilations["main"].let { it.output.allOutputs + it.runtimeDependencyFiles!! }
    dependsOn("desktopMainClasses")
    doLast { println(cp.joinToString(":")) }
}
