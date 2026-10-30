plugins {
    id("com.android.application")
}

// YouTube / YouTube Music 은 0.66 부터 웹 엔진(GeckoView) 없이 앱 플레이어로 재생한다:
// 로그인 = TV 방식(기기 코드), 목록 = YouTube TV 데이터, 재생 주소 = NewPipeExtractor, 재생 = ExoPlayer.
val newPipeVersion = "v0.26.5"

android {
    namespace = "com.dolphin.mplayer"
    // 0.65 까지 GeckoView 157 이 API 37.1 이상을 요구해 올린 것 (targetSdk 는 34 그대로 → 동작 변화 없음)
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.dolphin.mplayer"
        minSdk = 26
        targetSdk = 34
        // -PtestCode=61 : 에뮬레이터 시험 빌드를 설치된 번호로 (이용 안내 동의 창 · 카페 등록이 다시 뜨지 않게)
        versionCode = (findProperty("testCode") as String?)?.toInt() ?: 70
        versionName = "0.70"
    }

    // ABI 별 APK (0.65 까지 GeckoView 때문에 나눴던 것). 네이티브 라이브러리는 없어졌지만 업데이트가
    // 'D-Player-<버전>-arm64.apk' 처럼 CPU 이름으로 파일을 찾으므로 그대로 둔다.
    //  arm64: 차량용(기본) / arm: 32비트 차량용 / x64: 에뮬레이터 시험용
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") {
            dimension = "abi"
            ndk { abiFilters += "arm64-v8a" }
        }
        create("arm") {
            dimension = "abi"
            ndk { abiFilters += "armeabi-v7a" }
        }
        create("x64") {
            dimension = "abi"
            ndk { abiFilters += "x86_64" }
        }
    }

    // 서명 키: scripts/signing/dplayer.keystore (원래 개발 PC 의 ~/.android/debug.keystore 를 복사한 것).
    // 차량에 설치된 앱과 같은 키여야 자동 업데이트가 된다 → 이 파일을 잃어버리면 안 됨 (HANDOFF.md 참고).
    // 파일이 없으면 이 PC 의 디버그 키로 서명 (그 경우 기존 설치본 위로 업데이트 불가).
    val releaseKeystore = rootProject.file("scripts/signing/dplayer.keystore")
    signingConfigs {
        create("dplayer") {
            storeFile = releaseKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists()) signingConfigs.getByName("dplayer")
            else signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        // NewPipeExtractor 의존성들이 같은 이름의 라이선스 파일을 넣음
        resources { excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/*.md") }
    }

    // TV 데이터 해석 시험에 PC 임시 폴더 경로 넘기기 (-DytFixtures=...)
    testOptions {
        unitTests.all { test ->
            System.getProperty("ytFixtures")?.let { test.systemProperty("ytFixtures", it) }
            test.testLogging.showStandardStreams = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor 가 쓰는 최신 Java API (Android 8~10 에 없는 것) 를 앱에 넣어 줌
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    // USB/SD · YouTube 음악·영상 재생
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    // YouTube 라이브 방송 (HLS)
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    // YouTube 재생 주소 (서명 풀기 포함) — GPLv3, 그래서 D-Player 소스도 GPLv3 (0.66)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:$newPipeVersion")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    // Spotify 검색: 플랫폼 MediaBrowser 에는 search() 가 없어 AndroidX MediaBrowserCompat 사용 (0.54)
    implementation("androidx.media:media:1.7.0")
    // YouTube 로그인 창의 QR 코드 (google.com/device?user_code=…) — Apache 2.0 (0.69)
    implementation("com.google.zxing:core:3.5.3")
    // TV 데이터 해석 시험 (PC 에서 저장해 둔 응답으로, app/src/test)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
