plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

/*
 * Macrobenchmark. Nothing in here ships.
 *
 * com.android.test rather than com.android.library, and the difference matters: a test module
 * builds its own APK and drives the app from a separate process. That separation is the whole
 * point. Instrumentation running inside the app process cannot measure a cold start, because by
 * the time the instrumentation is alive the process it wanted to time already exists.
 */
android {
    namespace = "com.codexce.supportchat.benchmark"
    compileSdk = 35

    defaultConfig {
        /*
         * 24 on the app, 24 here would not work. Macrobenchmark needs the Perfetto tracing and
         * the shell profiling that only arrived in API 24 for some metrics and API 29 for
         * StartupTimingMetric to be trustworthy. 24 is the floor the library itself enforces;
         * in practice run this on API 29 or above, and prefer a physical device to an emulator,
         * where startup numbers are dominated by the host machine rather than by the app.
         */
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        /*
         * Only the benchmark build type exists here, and it maps onto the app's benchmark build
         * type - the one that inherits release's R8 and shrinking but is signed with the debug
         * key so it can be installed without a keystore.
         *
         * debug is explicitly removed. Leaving it available is how somebody eventually runs the
         * benchmark against a debug build, gets a cold start three or four times the real one,
         * and concludes the app is broken. Deleting the variant makes that mistake impossible
         * rather than merely discouraged.
         */
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
        }
    }

    targetProjectPath = ":app"

    /*
     * Lets this module install and drive the app itself instead of requiring a separate
     * orchestrator APK. Without it the build errors out asking for one.
     */
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.3")
}

androidComponents {
    beforeVariants(selector().all()) {
        // Only the benchmark variant is ever useful here; see the build types comment above.
        it.enable = it.buildType == "benchmark"
    }
}
