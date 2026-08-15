package com.codexce.supportchat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import com.codexce.supportchat.notifications.MessageWatchService

/**
 * What is left here after App Startup took the rest.
 *
 * This class used to switch on Firebase disk persistence and warm SharedPreferences. Both of
 * those now live in com.codexce.supportchat.startup as App Startup initializers, behind the
 * single androidx.startup provider, so they no longer cost a provider installation each and
 * their ordering is declared rather than inferred from manifest merge order.
 *
 * Two things genuinely belong in an Application and are still here: the debug-only StrictMode
 * policy, which has to be installed before any other code can violate it, and the foreground
 * tracker, which needs ActivityLifecycleCallbacks and therefore needs the Application object.
 */
class SupportChatApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installStrictMode()
        trackForeground()
    }

    /**
     * Debug builds only. Logs main-thread disk and network access. Never kills the process.
     *
     * This was written as detectAll().penaltyDeath(), on the theory that a logged violation is
     * a line in a stream nobody reads. The theory is fine; the implementation was wrong,
     * because penaltyDeath does not only punish this app's own code.
     *
     * StrictMode propagates violations back across Binder. When the app calls into
     * system_server, any disk read the system performs while servicing that call is attributed
     * to the caller and replayed in the caller's process - see
     * StrictMode.readAndHandleBinderCallViolations in the crash trace. On this device the
     * offender is the vendor stack, not us: MediaTek's ScnModule does a File.exists() from
     * PowerHalWrapper.amsBoostNotify while the framework resumes the activity, inside
     * ActivityThread.handleBindApplication. That is a 36ms disk read the app neither performs
     * nor can avoid, and penaltyDeath turned it into a guaranteed fatal crash on every debug
     * launch on this handset. Xiaomi's ForceDark, TurboSched and MQS hooks are the same class
     * of false positive.
     *
     * So: detectAll to keep the coverage, penaltyLog to keep the signal, and no penaltyDeath.
     * Filter logcat for "StrictMode policy violation" after a launch and read the stacks -
     * the ones ending in com.codexce.supportchat are ours and worth fixing; the ones ending in
     * com.mediatek or com.miui belong to the ROM and cannot be fixed from here.
     *
     * Never enabled in release: BuildConfig.DEBUG gates it, and R8 strips the whole block from
     * the release build because the condition folds to a constant false.
     *
     * VM policy stays on penaltyLog for the reason it always did. Leaked closables and
     * activity leaks are detected asynchronously, long after the code that caused them, so a
     * crash there points at the wrong place.
     */
    private fun installStrictMode() {
        if (!BuildConfig.DEBUG) return

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    /**
     * While the app is on screen its own repository listeners already deliver every message, so
     * the watch service is pure duplication - a second subscription and a permanent notification
     * for nothing. This releases it on the way in and restarts it on the way out.
     *
     * ActivityLifecycleCallbacks rather than ProcessLifecycleOwner, which would mean adding
     * lifecycle-process as a dependency for a counter this simple.
     *
     * The restart is wrapped because from Android 12 an app may not start a foreground service
     * from the background. The instant an activity stops still counts as foreground, so this
     * normally succeeds; if the system disagrees it throws rather than crashing the app, and the
     * service comes back on next launch. Granting the battery exemption the app asks for removes
     * the restriction entirely.
     */
    private fun trackForeground() {
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            private var firstStart = true

            /**
             * Cold-start fix.
             *
             * This used to fire a stopService binder transaction on the very first activity
             * start, which is inside the launch window and competing with the first frame. On a
             * cold start the watch service is not running yet - the process was only just
             * created - so that call had nothing to stop and cost a main-thread round trip for
             * nothing. It now only runs when the app is genuinely returning to the foreground.
             */
            override fun onActivityStarted(activity: Activity) {
                started++
                if (started == 1 && !firstStart) {
                    MessageWatchService.stop(this@SupportChatApplication)
                }
                firstStart = false
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) {
                    started = 0
                    runCatching { MessageWatchService.start(this@SupportChatApplication) }
                }
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
