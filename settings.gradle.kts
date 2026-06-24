pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "gardendless"
include(":app")
