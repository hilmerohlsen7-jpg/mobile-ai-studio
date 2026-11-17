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
        maven {
            setUrl("https://maven.mozilla.org/maven2")
            content { includeGroup("org.mozilla.geckoview") }
        }
        maven {
            setUrl("https://dl.frostwire.com/maven")
            content { includeGroup("com.frostwire") }
        }
    }
}
rootProject.name = "MobileAIStudio"
include(":app")
