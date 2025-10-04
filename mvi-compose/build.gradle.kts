import groovy.lang.Closure
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlin.multiplatform.android.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.palantirGitVersion)
    alias(libs.plugins.maven.central.publish)
    id("maven-publish")
}

group = "au.lovecraft"

val gitVersion: Closure<String> by extra
version = gitVersion().removePrefix("v")

kotlin {
    jvmToolchain(21)
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
            "-XXLanguage:+CustomEqualsInValueClasses"
        )
    }
    androidLibrary {
        namespace = "au.lovecraft.mvi.compose"
        compileSdk = 36
        minSdk = 29
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        add(projectDirPath) // Serve sources to debug inside browser
                    }
                }
            }
        }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            compileOnly(project(":mvi-core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.configure {
            dependsOn(jvmCommonMain)
        }
        jvmMain.configure {
            dependsOn(jvmCommonMain)
        }
        iosMain.configure {
            dependsOn(commonMain.get())
        }
        iosArm64Main {
            dependsOn(iosMain.get())
        }
        iosSimulatorArm64Main {
            dependsOn(iosMain.get())
        }
        wasmJsMain.dependencies {
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "mvi-compose", version.toString())

    pom {
        name.set("MVI (Compose Integration)")
        description.set("Jetpack Compose / Compose Multiplatform integration for MVI")
        inceptionYear.set("2025")
        url.set("https://github.com/lovecraft-au/mvi")
        licenses {
            license {
                name.set("GNU Lesser General Public License")
                url.set("https://www.gnu.org/licenses/lgpl-3.0.html")
                distribution.set("https://www.gnu.org/licenses/lgpl-3.0.html")
            }
        }
        developers {
            developer {
                id.set("chris-hatton")
                name.set("Christopher Hatton")
                url.set("https://github.com/chris-hatton")
            }
        }
        scm {
            url.set("https://github.com/lovecraft-au/mvi")
            connection.set("scm:git:git@github.com:lovecraft-au/mvi.git")
            developerConnection.set("scm:git:ssh://git@github.com:lovecraft-au/mvi.git")
        }
    }
}
