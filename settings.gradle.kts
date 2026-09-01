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
        // jeziellago/compose-markdown（JitPack）—— Markdown 渲染库（表格/图片/链接/HTML）
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "dsh-mobile"
include(":app")
