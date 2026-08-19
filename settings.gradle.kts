pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_21)) {
    throw GradleException(
        "Celeste requires JDK 21 or newer to run Gradle; " +
            "use scripts/celeste-env (current JVM: ${JavaVersion.current()}).",
    )
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Celeste"
include(":app")
