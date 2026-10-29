pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://repo.papermc.io/repository/maven-public/") { name = "Paper" }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://repo.papermc.io/repository/maven-public/") { name = "Paper" }
    }
}

rootProject.name = "cloud-core"

include(
    "cloudcore-api",
    "cloudcore-api-testkit",
    "cloudcore-protocol",
    "cloudcore-server-core",
    "cloudcore-bukkit",
    "cloudcore-client",
)
