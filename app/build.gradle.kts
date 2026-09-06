import org.gradle.api.GradleException
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties

abstract class VerifyStogApiEndpointTask : DefaultTask() {
    @get:Input
    abstract val endpoint: Property<String>

    @TaskAction
    fun verify() {
        val target = endpoint.get()
        val connection = try {
            URI("${target.trimEnd('/')}/feed")
                .toURL()
                .openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw GradleException("STOG_API_BASE_URL is unreachable: $target", error)
        }
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            if (status !in 200..499) {
                throw GradleException("STOG_API_BASE_URL returned HTTP $status: $target")
            }
        } catch (error: GradleException) {
            throw error
        } catch (error: Exception) {
            throw GradleException("STOG_API_BASE_URL health check failed: $target", error)
        } finally {
            connection.disconnect()
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.android.legacy-kapt") version "9.3.1"
}

val localSecrets = Properties().apply {
    rootProject.file("secrets.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use { load(it) }
}
val kakaoNativeAppKey = localSecrets.getProperty("KAKAO_NATIVE_APP_KEY").orEmpty()
val googleWebClientId = localSecrets.getProperty("GOOGLE_WEB_CLIENT_ID").orEmpty()
val googleMapsApiKey = localSecrets.getProperty("MAPS_API_KEY").orEmpty()
require(kakaoNativeAppKey.isNotBlank()) {
    "KAKAO_NATIVE_APP_KEY must be set in secrets.properties"
}
val stogApiTarget = providers.gradleProperty("stogApiTarget")
    .orNull
    ?.trim()
    ?.lowercase()
    ?: "phone"
require(stogApiTarget == "phone" || stogApiTarget == "emulator") {
    "stogApiTarget must be either phone or emulator"
}
val emulatorApiBaseUrl = "https://stog-backend-qoeu5cmuxq-as.a.run.app"
val stogApiBaseUrl = providers.gradleProperty("stogApiBaseUrl")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("STOG_API_BASE_URL")
        .orNull
        ?.trim()
        ?.takeIf { it.isNotBlank() }
    ?: if (stogApiTarget == "emulator") emulatorApiBaseUrl
    else {
        localSecrets.getProperty("STOG_API_BASE_URL")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    ?: error(
        "STOG_API_BASE_URL is required. Pass -PstogApiBaseUrl=<reachable backend URL> " +
            "or set it in secrets.properties.",
    )
require(stogApiBaseUrl != "https://unused.invalid") {
    "STOG_API_BASE_URL points to the placeholder https://unused.invalid. " +
        "Pass -PstogApiBaseUrl=<reachable backend URL> or set STOG_API_BASE_URL."
}
val stogApiHost = runCatching { URI(stogApiBaseUrl).host?.lowercase() }.getOrNull()
require(stogApiHost != "127.0.0.1") {
    "127.0.0.1:8080 is permanently forbidden for every APK target; " +
        "use the active public HTTPS backend for phones or exactly " +
        "http://10.0.2.2:8080 for emulators."
}
if (stogApiTarget == "phone") {
    require(stogApiBaseUrl.startsWith("https://")) {
        "Phone APK requires the active public HTTPS backend/tunnel URL; " +
            "use -PstogApiTarget=phone -PstogApiBaseUrl=https://<reachable-host>."
    }
    val phoneHost = stogApiHost
    val isPrivateIpv4 = phoneHost?.matches(
        Regex(
            """(?:10|127)\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}""",
        ),
    ) == true
    require(
        phoneHost != null &&
            phoneHost !in setOf("localhost", "127.0.0.1", "10.0.2.2", "0.0.0.0", "::1", "[::1]") &&
            !isPrivateIpv4,
    ) {
        "Phone APK requires a public HTTPS backend/tunnel hostname; " +
            "local, loopback, emulator, and LAN endpoints are forbidden."
    }
}
if (stogApiTarget == "emulator") {
    require(stogApiBaseUrl.trimEnd('/') == emulatorApiBaseUrl) {
        "Emulator APK requires exactly $emulatorApiBaseUrl; " +
            "use -PstogApiTarget=phone with the active HTTPS URL for physical devices."
    }
}
if (stogApiTarget == "phone") {
    tasks.register<VerifyStogApiEndpointTask>("verifyStogApiEndpoint") {
        endpoint.set(stogApiBaseUrl)
    }
}
val escapedKakaoNativeAppKey = kakaoNativeAppKey
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val escapedStogApiBaseUrl = stogApiBaseUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val escapedGoogleWebClientId = googleWebClientId
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val stogVersionCode = 3
val stogVersionName = "1.0.2"

val stogTopAppBarLogoResourceDirectory =
    layout.buildDirectory
        .dir("generated/res/stog-top-app-bar")
        .get()
        .asFile
val stogTopAppBarLogoResource = tasks.register<Sync>("prepareStogTopAppBarLogoResource") {
    from(rootProject.file("docs/img/STOG_HOME_LOGO.png")) {
        rename { "stog_logo_name.png" }
    }
    from(rootProject.file("docs/ds_rfs/stog_icon/STOG_app_icon_foreground.png")) {
        rename { "stog_search_ai_icon.png" }
    }
    into(stogTopAppBarLogoResourceDirectory.resolve("drawable-nodpi"))
}

val stogAssetDirectory = layout.buildDirectory.dir("generated/stog-assets")
val syncStogAssets = tasks.register<Sync>("syncStogAssets") {
    from(rootProject.file("docs/test/스토그캐릭터/STOBOT.png")) {
        rename { "STOBOT.png" }
    }
    from(rootProject.file("docs/img/STOBOT1.png")) {
        rename { "STOBOT1.png" }
    }
    from(rootProject.file("docs/test/스토그캐릭터/액티.png")) {
        rename { "character_acti.png" }
    }
    from(rootProject.file("docs/test/스토그캐릭터/허니.png")) {
        rename { "character_honey.png" }
    }
    from(rootProject.file("docs/test/스토그캐릭터/윙.png")) {
        rename { "character_wing.png" }
    }
    from(rootProject.file("docs/test/스토그캐릭터/버즈.png")) {
        rename { "character_buzz.png" }
    }
    from(rootProject.file("docs/test/스토그캐릭터/릴리.png")) {
        rename { "character_lily.png" }
    }
    from(rootProject.file("docs/test/스토그캐릭터/픽.png")) {
        rename { "character_pick.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-1.유명한 관광지를 먼저 방문하는 편인가요.png")) {
        rename { "question_q7.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-2.현지인만 아는 골목이나 숨은 명소를 더 좋아하나요.png")) {
        rename { "question_q12.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-3.사람이 많아도 인기 있는 장소라면 가는 편인가요.png")) {
        rename { "question_q8.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-4.여행에서는 사진이 잘나오는 장소가 중요한가요.png")) {
        rename { "question_q11.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-5.여행에서 맛집 탐방은 꼭 필요한 편인가요.png")) {
        rename { "question_q3.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-6.쇼핑이나 기념품 구입도 여행의 큰즐거움인가요.png")) {
        rename { "question_q4.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-7.걷거나 자연속 산책을 즐기는 편인가요.png")) {
        rename { "question_q1.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-8.박물관 ,문와유산처럼 역사적인 장소를 좋아하나요.png")) {
        rename { "question_q2.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-9.공방,축제,액티비티 같은 체험활동을 즐기나요.png")) {
        rename { "question_q5.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-10.여유롭게 쉬면서 풍경을 즐기는 여행을 선호하나요.png")) {
        rename { "question_q6.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-11.하루에 여러장소를 둘러보는 여행을 선호하나요.png")) {
        rename { "question_q9.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-12.계획을 세우고 일정대로 움직이는 여행이 편한가요.png")) {
        rename { "question_q10.png" }
    }
    from(rootProject.file("docs/test/설문지 질문 이미짖/13-13.여행우 사진이나 기록으로 추억을 남기는 편인가요.png")) {
        rename { "question_q13.png" }
    }
    into(stogAssetDirectory)
}

android {
    namespace = "com.stog.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.stog.app"
        minSdk = 24
        targetSdk = 37
        versionCode = stogVersionCode
        versionName = stogVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "KAKAO_NATIVE_APP_KEY",
            "\"$escapedKakaoNativeAppKey\"",
        )
        buildConfigField(
            "String",
            "STOG_API_BASE_URL",
            "\"$escapedStogApiBaseUrl\"",
        )
        buildConfigField(
            "String",
            "GOOGLE_WEB_CLIENT_ID",
            "\"$escapedGoogleWebClientId\"",
        )
        manifestPlaceholders["KAKAO_NATIVE_APP_KEY"] = kakaoNativeAppKey
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = googleMapsApiKey
    }
    sourceSets["main"].res.directories.add(stogTopAppBarLogoResourceDirectory.absolutePath)
    sourceSets["main"].res.directories.add(rootProject.file("img").absolutePath)
    sourceSets["main"].assets.directories.add(stogAssetDirectory.get().asFile.absolutePath)

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val copyVersionedDebugApk = tasks.register<Copy>("copyVersionedDebugApk") {
    dependsOn("packageDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/apk/versioned"))
    rename(
        "app-debug\\.apk",
        "STOG-v${stogVersionName}-code${stogVersionCode}-debug.apk",
    )
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    finalizedBy(copyVersionedDebugApk)
}

tasks.matching { it.name == "generateDebugBuildConfig" }.configureEach {
    inputs.property("stogApiBaseUrl", stogApiBaseUrl)
    inputs.property("stogApiTarget", stogApiTarget)
}

val h3AndroidJniDirectory = layout.buildDirectory.dir("generated/h3-jniLibs")
val extractH3AndroidJni = tasks.register<Sync>("extractH3AndroidJni") {
    from({
        val h3Jar = configurations.detachedConfiguration(
            dependencies.create("com.uber:h3:4.5.0"),
        ).resolve().single { it.name == "h3-4.5.0.jar" }
        zipTree(h3Jar).matching {
            include("android-arm/libh3-java.so")
            include("android-arm64/libh3-java.so")
        }
    }) {
        eachFile {
            path = path
                .replace("android-arm64/", "arm64-v8a/")
                .replace("android-arm/", "armeabi-v7a/")
        }
        includeEmptyDirs = false
    }
    into(h3AndroidJniDirectory)
}
android.sourceSets["main"].jniLibs.srcDir(h3AndroidJniDirectory.get().asFile)

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

tasks.named("preBuild").configure {
    dependsOn(stogTopAppBarLogoResource)
    dependsOn(syncStogAssets)
    dependsOn(extractH3AndroidJni)
    if (stogApiTarget == "phone") dependsOn("verifyStogApiEndpoint")
}

tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    finalizedBy("installDebug")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.fragment:fragment:1.5.7")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("com.uber:h3:4.5.0")
    kapt("androidx.room:room-compiler:2.8.4")
    testImplementation(libs.junit)
    testImplementation("androidx.work:work-testing:2.11.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("com.kakao.sdk:v2-user:2.24.0")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
