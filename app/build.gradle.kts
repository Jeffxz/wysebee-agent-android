plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The app's version, and the only place it is kept. Raise versionCode with every release:
// a phone updates only to a higher one.
val appVersion = "1.0.1"
val appVersionCode = 2

fun prop(name: String): String = (project.findProperty(name) as String?)?.trim() ?: ""

// The dev and local clouds' addresses are not in the repo: they are properties kept outside it
// (~/.gradle/gradle.properties, or -P), https only. Unit tests need neither; packaging a dev or
// local build refuses to go on without its own (below).
val devCloud = prop("WYSEBEE_AGENT_ANDROID_DEV_CLOUD_URL").trimEnd('/')
val localCloud = prop("WYSEBEE_AGENT_ANDROID_LOCAL_CLOUD_URL").trimEnd('/')

android {
    namespace = "com.wysebee.agent.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wysebee.agent.android"
        // takeScreenshot() and ACTION_IME_ENTER of an accessibility service need Android 11.
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    // The release key, never in the repo: its file and password are properties kept outside it
    // (~/.gradle/gradle.properties, or -P). Every update must be signed with this same key, or
    // phones refuse it. Without them a release build comes out unsigned (scripts/build.sh prod
    // refuses that).
    val releaseStore = prop("WYSEBEE_AGENT_ANDROID_STORE_FILE")
    val releaseKey = if (releaseStore.isEmpty()) null else signingConfigs.create("release") {
        storeFile = file(releaseStore)
        storePassword = prop("WYSEBEE_AGENT_ANDROID_STORE_PASSWORD")
        keyAlias = prop("WYSEBEE_AGENT_ANDROID_KEY_ALIAS").ifEmpty { "wysebee-agent-android" }
        keyPassword = prop("WYSEBEE_AGENT_ANDROID_KEY_PASSWORD").ifEmpty { prop("WYSEBEE_AGENT_ANDROID_STORE_PASSWORD") }
    }

    buildTypes {
        // Each build talks to one Wysebee Cloud, fixed when it is built, and can be pointed
        // nowhere else. Each is an app of its own (its package, name, data and keys), so all
        // three can be on one phone side by side.
        debug {
            // "Wysebee Agent Dev": the dev cloud, whose address is kept outside the repo.
            applicationIdSuffix = ".dev"
            buildConfigField("String", "CLOUD_URL", "\"${devCloud}\"")
        }
        create("local") {
            // "Wysebee Agent Local": a wysebee-cloud on a developer's computer, through a
            // tunnel whose address is kept outside the repo. Debuggable and signed with the
            // debug key, as the debug build is.
            initWith(getByName("debug"))
            applicationIdSuffix = ".local"
            matchingFallbacks += "debug"
            buildConfigField("String", "CLOUD_URL", "\"${localCloud}\"")
        }
        release {
            // "Wysebee Agent": production only.
            buildConfigField("String", "CLOUD_URL", "\"https://app.wysebee.com\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = releaseKey
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// A dev or local APK is made only with its cloud's address, and an https one.
mapOf("packageDebug" to ("WYSEBEE_AGENT_ANDROID_DEV_CLOUD_URL" to devCloud),
      "packageLocal" to ("WYSEBEE_AGENT_ANDROID_LOCAL_CLOUD_URL" to localCloud)).forEach { (task, cloud) ->
    tasks.matching { it.name == task }.configureEach {
        doFirst {
            val (name, url) = cloud
            if (!Regex("^https://[^/?#\\s\"]+$").matches(url)) {
                throw GradleException("$name must be the cloud's https address, set in ~/.gradle/gradle.properties (README, Build).")
            }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Scans Wysebee's pairing QR code with Google Play services' own camera screen: no camera
    // permission here, and no picture of anything reaches this app, only the code's text.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // ModuleInstall: ask Play services to fetch the scanner before the first scan needs it.
    implementation("com.google.android.gms:play-services-base:18.5.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json: Android's is a stub on the JVM.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
