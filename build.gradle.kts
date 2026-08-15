plugins {
    id("com.android.application") version "8.7.3" apply false
    /*
     * The :benchmark module's plugin. Declared here rather than in that module so its version is
     * pinned to the same AGP as the app - a test module built against a different AGP than the
     * app it measures fails to resolve the variant it is pointed at, which surfaces as an
     * unhelpful "no matching configuration" error rather than a version complaint.
     *
     * apply false: nothing at the root applies it, only :benchmark does.
     */
    id("com.android.test") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    // KSP version is pinned to the Kotlin version; Room's compiler runs through it.
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
