package com.cinnamon.vantracker

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.cinnamon.vantracker.data.Backend
import com.cinnamon.vantracker.data.FbConfig
import com.cinnamon.vantracker.notify.Notifier
import com.cinnamon.vantracker.ui.ThemeMode
import com.cinnamon.vantracker.ui.ThemePrefs
import com.google.android.gms.tasks.Tasks
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Walks through the app as an HOD, the GM and a driver against the LOCAL Firebase emulator
 * (demo data from screenshots/seed.js) and saves a screenshot of every screen into
 * files/shots. Run by .github/workflows/screenshots.yml – never against the real project.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTour {
    @get:Rule val rule = createEmptyComposeRule()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext
    private val device = UiDevice.getInstance(inst)
    private val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
    private val log = File(dir, "log.txt")
    private var scenario: ActivityScenario<MainActivity>? = null

    private fun note(s: String) { log.appendText(s + "\n"); android.util.Log.i("ScreenshotTour", s) }

    /** Closes Android's "… isn't responding" pop-up if a slow emulator shows one. */
    private fun dismissSystemDialogs() {
        repeat(3) {
            val anr = device.findObject(By.textContains("isn't responding")) ?: return
            (device.findObject(By.text("Wait")) ?: device.findObject(By.text("Close app")))?.click()
            Thread.sleep(800)
            if (anr.let { device.findObject(By.textContains("isn't responding")) } == null) return
        }
    }

    private fun shot(name: String, settle: Long = 1800) {
        rule.waitForIdle(); Thread.sleep(settle); dismissSystemDialogs(); rule.waitForIdle()
        device.takeScreenshot(File(dir, "$name.png"))
        note("shot $name")
    }

    private fun waitFor(text: String, substring: Boolean = false, timeout: Long = 15_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty() }

    /** Taps the first clickable node showing [text] (falls back to any node with it). */
    private fun tap(text: String, substring: Boolean = false) {
        dismissSystemDialogs()
        waitFor(text, substring)
        val clickable = rule.onAllNodes(hasText(text, substring) and hasClickAction())
        if (clickable.fetchSemanticsNodes().isNotEmpty()) clickable[0].performClick()
        else rule.onAllNodesWithText(text, substring)[0].performClick()
        rule.waitForIdle()
    }

    private fun swipeUp() {
        device.swipe(device.displayWidth / 2, device.displayHeight * 7 / 10, device.displayWidth / 2, device.displayHeight * 3 / 10, 40)
        rule.waitForIdle(); Thread.sleep(400)
    }

    /** Scrolls until [text] is on screen (works for lazy lists, where off-screen rows don't exist yet). */
    private fun scrollTo(text: String, substring: Boolean = false) {
        Thread.sleep(800)
        repeat(8) {
            val nodes = rule.onAllNodesWithText(text, substring)
            if (nodes.fetchSemanticsNodes().isNotEmpty()) {
                runCatching { nodes[0].performScrollTo() }
                rule.waitForIdle(); return
            }
            swipeUp()
        }
        waitFor(text, substring, 3000)
    }

    /** Selects next week's Friday (the demo Colombo trip) in the calendar. */
    private fun selectNextFriday() {
        waitFor("Booked")
        val c = Calendar.getInstance()
        val dow = c.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
        c.add(Calendar.DAY_OF_MONTH, (5 - dow + 7) % 7 + 7)
        if (c.get(Calendar.MONTH) != Calendar.getInstance().get(Calendar.MONTH)) {
            rule.onAllNodes(hasContentDescription("Next month"))[0].performClick(); rule.waitForIdle(); Thread.sleep(800)
        }
        tap(c.get(Calendar.DAY_OF_MONTH).toString())
    }

    private fun back() { device.pressBack(); rule.waitForIdle(); Thread.sleep(500) }

    private fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { note("FAILED $name: ${it.javaClass.simpleName}: ${it.message?.take(300)}") }
    }

    private fun launch() {
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitForIdle()
    }

    private fun signInAs(email: String) {
        Tasks.await(Backend.auth.signInWithEmailAndPassword(email, "demo1234"), 20, TimeUnit.SECONDS)
        launch()
    }

    private fun theme(m: ThemeMode) = inst.runOnMainSync { ThemePrefs.set(ctx, m) }

    @Test
    fun tour() {
        log.writeText("")
        note("status bar px: " + ctx.resources.getDimensionPixelSize(ctx.resources.getIdentifier("status_bar_height", "dimen", "android")))
        Backend.emulatorHost = "10.0.2.2"
        Backend.saveConfig(ctx, FbConfig("demo-api-key", "1:1234567890:android:5c0ffee5c0ffee", "demo-vantracker"))
        check(Backend.init(ctx)) { "Firebase init failed" }
        runCatching { Backend.auth.signOut() }
        theme(ThemeMode.LIGHT)

        // ---- sign-in screen
        step("login") { launch(); waitFor("Sign in", substring = true); shot("01-login") }

        // ---- Head of Department
        step("hod") { signInAs("hod@kandymyst.demo"); waitFor("Transport"); shot("02-hod-home", 3500) }
        step("hod-scroll") { scrollTo("Book my seat", substring = true); shot("03-hod-home-runs") }
        step("request") { tap("Request vehicle"); waitFor("Business purpose"); shot("04-request-form"); back() }
        step("calendar") { tap("Calendar"); waitFor("Booked"); shot("05-calendar") }
        step("calendar-day") {
            selectNextFriday()
            scrollTo("I'm going too")
            shot("06-calendar-colombo")
        }
        step("join") { tap("I'm going too"); waitFor("Business purpose"); shot("07-join-trip"); back() }
        step("delivery") {
            selectNextFriday(); scrollTo("Bring something", substring = true); tap("Bring something", substring = true)
            waitFor("What should be collected", substring = true); shot("08-delivery-request"); back()
        }
        step("my-requests") { tap("My requests"); waitFor("Laundry", substring = true); shot("09-my-requests") }
        step("trips") { tap("Trips"); waitFor("Bank – Finance", substring = true); shot("10-trips") }
        step("trip-detail") { tap("Bank – Finance", substring = true); shot("11-trip-detail", 6000); back() }
        step("dark") {
            theme(ThemeMode.DARK); tap("Home"); waitFor("Transport"); shot("12-hod-home-dark", 2500)
            tap("Calendar"); waitFor("Booked"); shot("13-calendar-dark")
            theme(ThemeMode.LIGHT)
        }

        // ---- General Manager
        step("gm") { runCatching { Backend.auth.signOut() }; signInAs("gm@kandymyst.demo"); waitFor("Transport"); shot("14-gm-home", 3500) }
        step("approvals") { tap("Approvals"); waitFor("Collect fresh produce", substring = true); shot("15-approvals") }
        step("approve-dialog") { tap("Approve"); waitFor("Approve request"); shot("16-approve-dialog"); tap("Cancel") }
        step("alerts") { tap("Alerts"); waitFor("without an approved trip", substring = true); shot("17-alerts") }
        step("more") { tap("More"); waitFor("Live map"); shot("18-more") }
        step("live") { tap("Live map"); shot("19-live-map", 7000); back() }
        step("reports") { tap("More"); tap("Reports", substring = true); shot("20-reports", 3000); back() }

        // ---- Driver
        step("driver") { runCatching { Backend.auth.signOut() }; signInAs("driver@kandymyst.demo"); waitFor("on duty", substring = true); shot("21-driver-home", 3500) }
        step("driver-trips") { scrollTo("Accept"); shot("22-driver-open-trip") }
        step("accept") { tap("Accept"); waitFor("Accepted", substring = true); shot("23-driver-accepted") }

        // ---- notification on the lock-screen shade
        step("notification") {
            Notifier.show(
                ctx, 4242, Notifier.CH_REQUESTS, "New trip request – approval needed",
                "Ashan Silva (F&B Service): Purchasing – Collect fresh produce for the dinner buffet", open = "approvals",
            )
            device.openNotification(); Thread.sleep(2500)
            device.takeScreenshot(File(dir, "24-notification.png")); note("shot 24-notification")
            back()
        }
        scenario?.close()
        note("done")
    }
}
