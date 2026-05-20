plugins {
    id("mihon.library")
    id("mihon.library.compose") // If needed for UI
}

android {
    namespace = "chimahon.local.ocr"

    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":chimahon"))
    implementation(project(":presentation-core"))
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.bundles.coroutines)
    implementation(libs.injekt.core)
    implementation(libs.bundles.ktor)
    // TFLite dependencies if needed, or rely on bundled .so
}
