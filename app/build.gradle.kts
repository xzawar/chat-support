plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.codexce.supportchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codexce.supportchat"
        minSdk = 24
        targetSdk = 35
        versionCode = 42
        versionName = "7.0.2"
        vectorDrawables { useSupportLibrary = true }

        /*
         * Where the app looks for a newer build on startup.
         *
         * CHANGE THIS to your own host. It is a build-time constant, so getting it wrong means
         * shipping an APK that can never find an update.
         *
         * Point it at YOUR domain, not directly at github.com, even though the files live in a
         * GitHub Release. The URL is compiled into every installed copy and can never be changed
         * retroactively, so it has to be one you control forever: a redirect can be repointed at a
         * new repo, a new owner or a different host at any time, and every old install follows.
         * Hardcoding github.com/<owner>/<repo> makes renaming the repository a breaking change for
         * users who have already installed the app.
         *
         * versionCode is what the check actually compares - it is a number and it only ever goes
         * up. versionName is shown to the user and never parsed; "7.0.10" vs "7.0.9" would sort
         * backwards as a string, which is exactly the bug versionCode exists to prevent.
         */
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://keykraftt.com/app/latest.json\"",
        )
    }

    /*
     * Release signing, supplied by the environment rather than committed.
     *
     * Android will only install an update over an existing app when both are signed by the SAME
     * key. This is not a policy that can be worked around: a differently-signed APK fails with
     * INSTALL_FAILED_UPDATE_INCOMPATIBLE and the only remedy is uninstalling first, which erases
     * the user's local data. So every release built for the updater must use one keystore, kept
     * safe, forever.
     *
     * findByName below means an unsigned local `assembleRelease` still works when the variables
     * are absent: the block is simply not created, and Gradle falls back to producing an unsigned
     * APK instead of failing to configure. CI sets the four variables and gets a signed one.
     */
    val releaseKeystore = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let(::file)

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // OFF by default, so a debug build talks to the real chat-support-1 project.
            //
            // Turn this to true ONLY while `firebase emulators:start --only auth,firestore,database`
            // is actually running, otherwise every Auth, Firestore and database call is pointed at
            // a host that is not listening and the app reports that it cannot reach the server.
            //
            // EMULATOR_HOST is 10.0.2.2 because that is how the Android emulator reaches the host
            // machine's localhost; on a physical device, replace it with your machine's LAN
            // address (see EMULATOR.md).
            buildConfigField("boolean", "USE_EMULATORS", "false")
            buildConfigField("String", "EMULATOR_HOST", "\"10.0.2.2\"")
        }

        release {
            // Never in a release build: a shipped APK must not be pointable at a laptop.
            buildConfigField("boolean", "USE_EMULATORS", "false")
            buildConfigField("String", "EMULATOR_HOST", "\"\"")
            // Only when the keystore variables were provided; see signingConfigs above.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        /*
         * A release build that Macrobenchmark is allowed to install and measure.
         *
         * This exists because there is no honest way to measure startup otherwise. The debug
         * build is not a slower release build, it is a different program: no R8, no resource
         * shrinking, and no baseline profile applied, so it runs interpreted until the JIT
         * catches up. A cold start measured there tells you nothing about the one users get.
         *
         * initWith(release) inherits the real shrinking and the real ProGuard configuration, so
         * what is measured is the shipping code. Only two things differ, and both are forced by
         * the tooling rather than chosen:
         *
         *   signingConfig - it borrows the debug key purely so `gradlew :benchmark:connected...`
         *   can install it without a release keystore. It changes nothing about the code.
         *
         *   matchingFallbacks - the :benchmark module has no "benchmark" build type of its own
         *   to resolve against, and without this the dependency resolution fails outright.
         *
         * isDebuggable is left false deliberately. Setting it true is the usual mistake here and
         * it silently reintroduces the interpreter. Profiling attaches through the
         * <profileable android:shell="true"/> entry in the manifest instead.
         */
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Required for the USE_EMULATORS / EMULATOR_HOST fields above.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")

    // The Android 12 splash screen, backported. This owns the window between the launcher tap
    // and the first composed frame -- the gap that previously showed a bare window_background.
    // It does NOT replace StartupScreen: that one covers session restore, which is unbounded
    // network work, and holding a frozen system splash across it would look like a hang.
    implementation("androidx.core:core-splashscreen:1.0.1")

    /*
     * Installs the baseline profile at first run.
     *
     * This was inert. The dependency was here and the comment said dropping a
     * baseline-prof.txt into src/main/ was the only remaining step - and that step was never
     * taken, so the library shipped, ran, found no profile, and did nothing on every launch.
     * src/main/baseline-prof.txt now exists, and AGP bundles it into the APK automatically
     * with no plugin or extra configuration required.
     *
     * What it buys: the listed methods are compiled ahead of time at install, so the first
     * launch runs compiled code instead of interpreting it while the JIT warms up. That is
     * worth roughly 30% off cold start, and unlike everything else in this release it costs
     * nothing at runtime.
     */
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    /*
     * One ContentProvider instead of one per library.
     *
     * Firebase disk persistence and the SharedPreferences warm-up now run as App Startup
     * initializers behind androidx.startup.InitializationProvider rather than out of
     * Application.onCreate. See com.codexce.supportchat.startup.BackendInitializer for why the
     * ordering matters and why one of them must stay eager.
     */
    implementation("androidx.startup:startup-runtime:1.2.0")

    /*
     * Declared explicitly, and it must stay that way.
     *
     * The Kotlin Compose compiler plugin rewrites every @Composable lambda into an
     * androidx.compose.runtime.internal.ComposableFunctionN. Those interfaces live in
     * compose-runtime, so the plugin emits references to a module that was never named here -
     * it only arrived transitively through ui and material3. When the IDE resolves a slightly
     * different graph to Gradle, that transitive edge is the first thing to go, and the result
     * is exactly the pair of errors on setContent:
     *
     *   Cannot access class 'androidx.compose.runtime.internal.ComposableFunction0'
     *   Argument type mismatch: actual '() -> Unit', expected 'ComposableFunction0<Unit>'
     *
     * The second error is the first one's fallout: with the interface unresolvable the compiler
     * cannot see that the lambda it just rewrote satisfies the parameter, so a perfectly valid
     * setContent block looks like a type error.
     *
     * Naming runtime directly puts it on the compile classpath in its own right instead of by
     * inheritance. The BOM still supplies the version, so this cannot drift from the rest.
     */
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    // Google-owned QR scanner: no camera permission prompt or custom camera UI required.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Room is the single source of truth the UI reads from; Firebase writes into it.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coil decodes gallery wallpapers scaled to the composable bounds, not full resolution.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Google sign-in via Credential Manager (replaces the deprecated GoogleSignInClient).
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Firebase is the entire backend now: Auth, Firestore and Realtime Database. There is no
    // HTTP client in the app any more.
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
