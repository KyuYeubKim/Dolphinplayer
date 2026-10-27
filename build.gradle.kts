buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 내장 Kotlin(2.2) 대신 2.4 컴파일러 사용 (GeckoView 를 쓰던 0.65 까지 필요했음, 그대로 둠)
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
