import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

/*
 * Client configuration.
 *
 * Only the keys in CLIENT_CONFIG_KEYS are ever copied into BuildConfig. Everything else in the
 * env files is ignored, so a private secret accidentally pasted into `.env` can never be packaged
 * into the APK. Values are resolved in this order (first non-null wins):
 *   1. Environment variable (CI / release pipelines)
 *   2. `.env` in the repository root (git-ignored, developer machine)
 *   3. `.env.example` (committed, placeholders only - all empty)
 *
 * Debug builds additionally read DEV_BACKEND_BASE_URL (default: the local test backend reached
 * from the Android emulator). Release builds never see the dev value.
 */
val CLIENT_CONFIG_KEYS = listOf(
  "BACKEND_BASE_URL",
  "PAYMENT_PROVIDER",
  "PAYMENT_PUBLIC_KEY",
  "MAPS_API_KEY",
  "PLACES_API_KEY",
  "ROUTING_API_KEY",
  "AUTH_PROVIDER",
)

fun loadEnvFile(name: String): Properties =
  Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
  }

val envLocal = loadEnvFile(".env")
val envExample = loadEnvFile(".env.example")

fun configValue(key: String): String =
  (System.getenv(key) ?: envLocal.getProperty(key) ?: envExample.getProperty(key) ?: "").trim()

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.paylift.rhwe"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    CLIENT_CONFIG_KEYS.forEach { key ->
      buildConfigField("String", key, configValue(key).asBuildConfigString())
    }
  }

  signingConfigs {
    // Release signing is only configured when the CI/release environment supplies it.
    // Without these variables the release artifacts are produced unsigned (still verifiable).
    val keystorePath = System.getenv("KEYSTORE_PATH")
    if (!keystorePath.isNullOrBlank()) {
      create("release") {
        storeFile = file(keystorePath)
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfigs.findByName("release")?.let { signingConfig = it }
      buildConfigField("boolean", "DEV_TOOLS_ENABLED", "false")
      buildConfigField("boolean", "DEMO_MODE", "false")
      buildConfigField("String", "DEV_BACKEND_BASE_URL", "\"\"")
    }
    debug {
      // Uses the Android SDK's default debug keystore (~/.android/debug.keystore).
      buildConfigField("boolean", "DEV_TOOLS_ENABLED", "true")
      buildConfigField("boolean", "DEMO_MODE", "false")
      val devUrl = (System.getenv("DEV_BACKEND_BASE_URL") ?: envLocal.getProperty("DEV_BACKEND_BASE_URL")
        ?: "http://10.0.2.2:8080/").trim()
      buildConfigField("String", "DEV_BACKEND_BASE_URL", devUrl.asBuildConfigString())
    }
    // DEMO build: debug-signed, separate application id, in-process DemoEngine (src/demo + src/devShared).
    // Never published; release builds cannot contain any of this code.
    create("demo") {
      initWith(getByName("debug"))
      applicationIdSuffix = ".demo"
      versionNameSuffix = "-demo"
      matchingFallbacks += listOf("debug")
      signingConfig = signingConfigs.getByName("debug")
      buildConfigField("boolean", "DEV_TOOLS_ENABLED", "true")
      buildConfigField("boolean", "DEMO_MODE", "true")
      buildConfigField("String", "DEV_BACKEND_BASE_URL", "\"\"")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  sourceSets {
    // Exported Room schemas are needed by MigrationTestHelper.
    getByName("test").assets.directories.add("$projectDir/schemas")
    getByName("androidTest").assets.directories.add("$projectDir/schemas")
    // Dev-only code shared by the debug and demo build types (never release).
    listOf("debug", "demo").forEach { name ->
      getByName(name).java.directories.add("$projectDir/src/devShared/java")
      getByName(name).kotlin.directories.add("$projectDir/src/devShared/java")
    }
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  lint {
    abortOnError = true
    checkReleaseBuilds = true
    warningsAsErrors = false
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
  arg("room.generateKotlin", "true")
}

// Release configuration guard: a release artifact must never point at a test payment provider,
// a dev auth provider or a plaintext backend. Empty values are allowed (the app then reports the
// service as "not configured" at runtime instead of pretending to work).
val verifyReleaseConfig by tasks.registering {
  val backend = configValue("BACKEND_BASE_URL")
  val payment = configValue("PAYMENT_PROVIDER").lowercase()
  val auth = configValue("AUTH_PROVIDER").lowercase()
  doLast {
    require(backend.isEmpty() || backend.startsWith("https://")) {
      "BACKEND_BASE_URL must use https:// for release builds (was '$backend')."
    }
    require(payment !in setOf("test", "mock", "sandbox", "dev")) {
      "PAYMENT_PROVIDER='$payment' is a test provider and cannot be used in a release build."
    }
    require(auth !in setOf("test", "mock", "dev")) {
      "AUTH_PROVIDER='$auth' is a development provider and cannot be used in a release build."
    }
  }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyReleaseConfig) }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)
  debugImplementation(libs.logging.interceptor)
  "demoImplementation"(libs.logging.interceptor)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.room.testing)
  androidTestImplementation(libs.mockwebserver)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// Keep Robolectric test JVMs small and sequential (memory-constrained machines / CI runners).
tasks.withType<Test>().configureEach {
  maxParallelForks = 1
  maxHeapSize = "1536m"
  forkEvery = 40
}
