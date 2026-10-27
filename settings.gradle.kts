pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor (YouTube 재생 주소·검색, GPLv3) — JitPack 은 NewPipe 묶음만 받음 (0.66)
        maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } }
    }
}
rootProject.name = "DolphinMPlayer"
include(":app")
