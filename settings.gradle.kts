pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

// The applicationId and the Kotlin package stay `com.mydrop.vpn`: Android identifies an app by
// its applicationId, and changing it would make every existing install a different app that
// cannot be updated in place. Everything the user or a developer actually reads says Yumi.
rootProject.name = "Yumi"
include(":app")
