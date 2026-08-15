package com.codexce.supportchat.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regenerates app/src/main/baseline-prof.txt.
 *
 * WHAT A BASELINE PROFILE IS, briefly, because the file it produces looks like noise
 *
 * Android ships app code as DEX and interprets or JITs it at runtime. Code on the startup path
 * is therefore slow exactly when it is most visible: the first time it runs. A baseline profile
 * is a list of the classes and methods worth compiling ahead of time, installed with the app and
 * applied on first launch, so that code is already native by the time the user sees anything.
 * Google measures roughly 30% faster execution of the covered paths from the very first launch.
 *
 * The profile is also consumed at build time to lay out the DEX files so that startup code sits
 * together, which reduces page faults during launch independently of the AOT compilation.
 *
 * WHY THIS FILE EXISTS RATHER THAN A HAND-WRITTEN PROFILE
 *
 * The checked-in baseline-prof.txt was written by hand from a reading of the startup path. That
 * is a reasonable starting point and it is certainly better than no profile, but it is a guess:
 * it covers what a person believed runs at startup, not what actually runs. This generator
 * records the real thing by driving the app and capturing every method the runtime touches -
 * including the Compose and Firebase internals nobody would think to list, which are a large
 * share of the benefit.
 *
 * HOW TO RUN
 *
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=\
 *       com.codexce.supportchat.benchmark.BaselineProfileGenerator
 *
 * REQUIREMENTS, both of which are easy to miss and produce confusing failures
 *
 *   1. A rooted device or an emulator running a userdebug/AOSP image. Profile capture needs
 *      shell access the platform does not grant on a production build. On a retail phone this
 *      test fails with a permissions error that does not obviously say "use a different device".
 *   2. API 28 or higher.
 *
 * AFTERWARDS
 *
 * The run writes a profile into the benchmark module's build outputs - look for
 * benchmark/build/outputs/managed_device_android_test_additional_output/ or the path printed at
 * the end of the run. Copy it over app/src/main/baseline-prof.txt and commit it. AGP picks that
 * file up automatically on release builds; no plugin and no extra wiring is involved.
 *
 * Regenerate it when the startup path changes in a real way - a new initializer, a different
 * start destination, a navigation restructure. A stale profile is not harmful, it simply stops
 * covering the code that now runs, and the benefit quietly decays.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

	@get:Rule
	val baselineProfileRule = BaselineProfileRule()

	@Test
	fun generate() = baselineProfileRule.collect(
		packageName = PACKAGE,
		// Also emit a startup profile. This is the one used at build time to order the DEX
		// layout, which is a separate win from the AOT compilation and costs nothing extra here.
		includeInStartupProfile = true,
	) {
		pressHome()
		startActivityAndWait()

		// Everything below is about coverage, not about asserting behaviour. Whatever this block
		// exercises is what ends up in the profile, so it should walk the path a real launch
		// takes and no further.
		device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 10_000)
		device.waitForIdle()

		// Sit still while the startup screen clears and the inbox composes for the first time.
		// STARTUP_MIN_MILLIS alone is 650ms and session restore follows it, so returning earlier
		// would capture the splash and miss the screen the user actually waits for - which is
		// precisely the code most worth compiling ahead of time.
		Thread.sleep(3_000)
	}

	private companion object {
		const val PACKAGE = "com.codexce.supportchat"
	}
}
