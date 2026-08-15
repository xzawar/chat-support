package com.codexce.supportchat.benchmark

import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup measurement.
 *
 * This exists because every claim made about startup in this project up to now has been an
 * argument rather than a number. "The splash feels slow", "the wallpaper decode is the problem",
 * "the deferred transition is fine" - all plausible, none measured. A macrobenchmark turns those
 * into figures that either move or do not when a change lands.
 *
 * HOW TO RUN
 *
 *   ./gradlew :benchmark:connectedBenchmarkAndroidTest
 *
 * It must run on a physical device. An emulator shares a host CPU with everything else running
 * on the machine, and the resulting numbers vary by more than the changes being measured, which
 * makes them worse than no numbers at all - they look authoritative and are noise.
 *
 * WHAT COMES BACK
 *
 *   timeToInitialDisplayMs   first frame on screen. For this app that is StartupScreen: a moving
 *                            bar over an empty background.
 *   timeToFullDisplayMs      the frame where the app is actually usable. Emitted only because
 *                            NavGraph calls ReportDrawnWhen { workspaceReady }. Remove that call
 *                            and this metric silently disappears from the output rather than
 *                            failing, which is an easy regression to miss.
 *
 * TTFD is the number that matters. TTID can be improved by drawing the placeholder sooner, which
 * helps nobody.
 *
 * TARGETS
 *
 *   cold  under 500ms
 *   warm  under 200ms
 *   hot   under 150ms
 *
 * Read the P95 alongside the median, not instead of it. A median of 400ms with a P95 of 1.4s is
 * a worse product than a median of 550ms with a P95 of 600ms: the first ships a bad experience
 * to a visible slice of users on every launch, and it is the slice most likely to be on the
 * cheapest hardware. The two should sit close together.
 *
 * ITERATIONS
 *
 * Ten. Enough for the distribution to mean something, few enough to run while working. Raise it
 * before trusting a small difference - a 5% change across ten iterations is noise.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

	@get:Rule
	val benchmarkRule = MacrobenchmarkRule()

	/**
	 * The real case: process created from nothing.
	 *
	 * This is what a user gets after a reboot, after the system reclaims the app, or on first
	 * launch of the day - which is most launches for an app like this one. Application.onCreate,
	 * the App Startup initializers, Firebase, the first composition and session restore all land
	 * inside this number, so it is the one to optimise against.
	 */
	@Test
	fun coldStartup() = startup(StartupMode.COLD)

	/**
	 * Process alive, activity recreated. Isolates composition and layout cost from process and
	 * library initialisation: if cold moves and warm does not, the change was in startup work
	 * rather than in the UI.
	 */
	@Test
	fun warmStartup() = startup(StartupMode.WARM)

	/** Everything resident, activity just brought forward. Effectively a floor for the others. */
	@Test
	fun hotStartup() = startup(StartupMode.HOT)

	private fun startup(mode: StartupMode) = benchmarkRule.measureRepeated(
		packageName = PACKAGE,
		metrics = listOf(StartupTimingMetric()),
		iterations = 10,
		startupMode = mode,
		setupBlock = {
			// Start from the launcher every time. Without this the device is left wherever the
			// previous iteration finished, and a run that begins with the app already foregrounded
			// is not the scenario being measured.
			pressHome()
		},
	) {
		startActivityAndWait()
		// startActivityAndWait returns on the first frame. The app is not finished at that point -
		// the startup screen is still up while the session resolves - so wait for real content
		// before ending the iteration, or the trace is cut short and TTFD is never captured.
		device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 10_000)
	}

	private companion object {
		const val PACKAGE = "com.codexce.supportchat"
	}
}
