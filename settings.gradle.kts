pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://mvnrepo.alibaba-inc.com/api/protocol/1/MAVEN/thirdparty") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://mvnrepo.alibaba-inc.com/api/protocol/1/MAVEN/thirdparty") }
    }
}

rootProject.name = "alian"
include(":app")
