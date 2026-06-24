import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.pvzge.gardendless"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties()
                props.load(propsFile.inputStream())
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else {
                storeFile = rootProject.file("keystore.jks")
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "pvzge123"
                keyAlias = System.getenv("KEY_ALIAS") ?: "pvzge"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "pvzge123"
            }
        }
    }

    defaultConfig {
        applicationId = "com.pvzge.gardendless"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "0.10.0"
    }

    buildFeatures {
        buildConfig = true
    }

    aaptOptions {
        noCompress += "zip"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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

val createGameZip by tasks.registering(Zip::class) {
    group = "game"
    description = "Creates pvzge_web.zip from ../pvzge_web/docs (run once, ~2 min)"
    from(rootProject.file("../pvzge_web/docs"))
    archiveFileName.set("pvzge_web.zip")
    destinationDirectory.set(file("src/main/assets"))
    exclude(".nojekyll", "CNAME")
}

tasks.named("preBuild") {
    dependsOn(createGameZip)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
}
