plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pvzge.gardendless"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pvzge.gardendless"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.10.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

val zipGameAssets by tasks.registering(Zip::class) {
    from(rootProject.file("../pvzge_web/docs"))
    archiveFileName.set("pvzge_web.zip")
    destinationDirectory.set(file("src/main/assets"))
    exclude(".nojekyll", "CNAME")
}

tasks.named("preBuild") {
    dependsOn(zipGameAssets)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
}
