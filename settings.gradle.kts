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
    }
}

rootProject.name = "SupportChatAgent"
include(":app")

/*
 * Startup and jank measurement. Test-only: it produces no artifact that ships, and it is never
 * on the app's compile or runtime classpath - the dependency points the other way.
 *
 * It exists because the startup work in this release is otherwise unfalsifiable. "The app feels
 * faster" is not a result; a cold-start median with a P95 next to it, taken from a real release
 * build on a real device, is. This module is also what regenerates app/src/main/baseline-prof.txt
 * when the launch path changes shape.
 */
include(":benchmark")
