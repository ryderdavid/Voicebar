package com.ryderdavid.voicebar

import android.app.Activity
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h860dp-port-xxhdpi")
class DockViewTest {
    private lateinit var activity: Activity
    private lateinit var dock: DockView
    private val keys = mutableListOf<Any>()
    private val edits = mutableListOf<Bar.EditAction>()
    private val talk = mutableListOf<String>()
    private val stick = mutableListOf<String>()
    private val dictationActions = mutableListOf<String>()
    private val typed = mutableListOf<String>()
    private var capitalize = false
    private var settings = false
    private var picker = false
    private var cleared = false
    private var style: Boolean? = null
    private var gboard = false
    private var toGboard = 0
    private var downTime = 0L

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        dock = DockView(activity, null)
        dock.testSetBottomInset(0)
        dock.listener = object : Bar.Listener {
            override fun onKey(key: Bar.Key, repeat: Boolean) { keys.add(key) }
            override fun onSwitchKeyboard() { picker = true }
            override fun onOpenSettings() { settings = true }
            override fun onEdit(action: Bar.EditAction) { edits.add(action) }
            override fun onDeleteAll() { cleared = true }
            override fun onSwitchToGboard() { toGboard++ }
            override fun onTypeAfterTalk() { talk.add("type") }
            override fun onSendAfterTalk() { talk.add("send") }
            override fun onDismissCommand() { talk.add("dismiss") }
            override fun shouldCapitalize() = capitalize
            override fun onTalkStart(): Boolean { talk.add("start"); return true }
            override fun onFixStart(): Boolean { talk.add("fix"); return true }
            override fun onTalkEnd(cancel: Boolean) { talk.add(if (cancel) "cancel" else "end") }
            override fun onTalkFinish(polish: Boolean) { talk.add(if (polish) "polish" else "end") }
            override fun onQuiet() { talk.add("quiet") }
            override fun onStickStart() { stick.add("start") }
            override fun onStickStep(cols: Int, lines: Int, selecting: Boolean) { stick.add("step $cols $lines ${if (selecting) "sel" else "aim"}") }
            override fun onStickTap() { stick.add("tap") }
            override fun onStickSelect() { stick.add("select") }
            override fun onDictation(action: Bar.DictationAction, time: Long) { dictationActions.add("$action $time") }
            override fun onRepeatDictation() { dictationActions.add("repeat") }
            override fun onText(text: String) { typed.add(text) }
            override fun onKeysShown(open: Boolean) { talk.add(if (open) "keys open" else "keys closed") }
            override fun onStickEnd() { stick.add("end") }
        }
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        idle(0)
    }

    private val d get() = activity.resources.displayMetrics.density
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun touch(action: Int, p: PointF) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
        dock.dispatchTouchEvent(e); e.recycle()
    }
    private fun at(act: DockView.Act, ch: Char = ' '): PointF {
        val r: RectF = assertNotNull(dock.testItem(act, ch), "$act is on screen")
        return PointF(r.centerX(), r.centerY())
    }
    private fun tap(act: DockView.Act, ch: Char = ' ') { val p = at(act, ch); touch(MotionEvent.ACTION_DOWN, p); touch(MotionEvent.ACTION_UP, p); idle(20) }
    /** Taps ☰, then [k] in its list (a [DockView.MenuItem] or [DockView.MenuEdit]). */
    private fun menu(k: Any) {
        if (!dock.testMenu) { tap(DockView.Act.MORE); relayout() }
        val p = assertNotNull(dock.testMenuAt(k), "$k is in the menu")
        touch(MotionEvent.ACTION_DOWN, p); touch(MotionEvent.ACTION_UP, p); idle(20)
        relayout()
    }
    /** Pulls the keyboard's handle down, far enough to close it. */
    private fun pullKeysDown() {
        val h = dock.testKeys.testHandle
        val from = PointF(h.centerX(), h.centerY() + dock.testBarTop)
        val to = PointF(from.x, from.y + 120 * d)
        touch(MotionEvent.ACTION_DOWN, from); touch(MotionEvent.ACTION_MOVE, to); touch(MotionEvent.ACTION_UP, to); idle(20)
    }
    private fun keysMenuKey() = dock.testKeys.testKind(PopKeys.Kind.MENU)!!.let { PointF(it.centerX(), it.centerY() + dock.testBarTop) }

    private fun relayout() {
        idle(50)
        dock.settle()
        dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
        dock.layout(0, 0, dock.width, dock.measuredHeight)
        dock.settle()
        idle(20)
    }

    @Test fun atRestSevenEqualButtonsInOneRow() {
        assertEquals((52 * d).toInt(), dock.height)
        val order = listOf(DockView.Act.MORE, DockView.Act.SPACE_ALT, DockView.Act.CURSOR, DockView.Act.KEYBOARD, DockView.Act.MIC, DockView.Act.BACKSPACE, DockView.Act.ENTER)
        val rects = order.map { assertNotNull(dock.testItem(it), "$it") }
        rects.zipWithNext().forEach { (a, b) -> assertTrue(a.right < b.left, "⋮ · space · Cursor ABC Voice · ⌫ · ↵") }
        rects.forEach { assertEquals(rects[0].width(), it.width(), 0.5f) }
        assertTrue(abs(rects[3].centerX() - dock.width / 2f) < 2 * d, "ABC sits dead center")
        dock.testSetBottomInset((24 * d).toInt())
        relayout()
        assertEquals((76 * d).toInt(), dock.height, "plus the navigation area under it, in the same surface")
    }

    private fun floating(collapsed: Boolean = false) {
        Prefs.get(activity).edit().putString(Prefs.BAR_STYLE, Prefs.BAR_STYLE_FLOATING).putBoolean(Prefs.BAR_COLLAPSED, collapsed).commit()
        dock.reloadSettings()
        relayout()
    }

    @Test fun dockedIsNotCollapsible() {
        assertFalse(dock.testCollapsed)
        assertNull(dock.testCollapsedPill())
        tap(DockView.Act.MORE); relayout()
        assertFalse(DockView.MenuItem.COLLAPSE in dock.testMenuItems())
        assertTrue(DockView.MenuItem.FLOAT in dock.testMenuItems())
    }

    @Test fun floatingIsASmallPillOfMenuCursorAbcVoiceAndOneOwnKey() {
        val docked = assertNotNull(dock.testItem(DockView.Act.MIC))
        floating()
        assertEquals(activity.resources.displayMetrics.heightPixels, dock.height, "the whole screen, see-through: it can be moved anywhere")
        val (content, touch) = assertNotNull(dock.floatingInsets())
        val order = listOf(DockView.Act.GRIP, DockView.Act.MORE, DockView.Act.CURSOR, DockView.Act.KEYBOARD, DockView.Act.MIC, DockView.Act.ENTER)
        val rects = order.map { assertNotNull(dock.testItem(it), "$it") }
        rects.zipWithNext().forEach { (a, b) -> assertTrue(a.right < b.left, "⠿ · ☰ · Cursor · ABC · Voice · own key") }
        rects.drop(1).forEach { assertEquals(docked.width(), it.width(), 0.5f, "the docked keys' size") }
        assertEquals(docked.height(), rects[1].height(), 0.5f)
        // parked at the bottom: the app sits above it, and only the pill takes touches
        assertEquals(1, touch.size)
        assertTrue(touch[0].contains(rects[3].centerX(), rects[3].centerY()) && content < touch[0].top && content > touch[0].top - 10 * d)
        assertTrue(touch[0].bottom <= dock.height && rects[3].bottom > dock.height - 60 * d, "at the bottom")
        assertFalse(dock.testHas(DockView.Act.SPACE_ALT) || dock.testHas(DockView.Act.BACKSPACE), "just the one own key")
        assertTrue(abs((rects[0].left + rects.last().right) / 2 - dock.width / 2f) < 8 * d, "in the middle")
        tap(DockView.Act.ENTER)
        assertEquals<List<Any>>(listOf(Bar.Key.DELETE), keys, "the own key starts as Backspace")
        // dictating works the same, inside the pill
        tap(DockView.Act.MIC)
        relayout()
        assertTrue(dock.testTalking)
        assertTrue(dock.testHas(DockView.Act.ENTER) && dock.testHas(DockView.Act.CURSOR))
        assertFalse(dock.testHas(DockView.Act.MORE), "☰ gives way to Cursor and ABC")
    }

    @Test fun collapsingFromTheMenuLeavesTheRecordPillAndItsMenuOpensTheBarAgain() {
        floating()
        menu(DockView.MenuItem.COLLAPSE)
        assertTrue(dock.testCollapsed)
        assertFalse(dock.testMenu)
        assertTrue(Prefs.get(activity).getBoolean(Prefs.BAR_COLLAPSED, false), "remembered")
        for (a in listOf(DockView.Act.CURSOR, DockView.Act.KEYBOARD, DockView.Act.ENTER)) assertFalse(dock.testHas(a), "$a is gone")
        val pill = assertNotNull(dock.testCollapsedPill())
        assertEquals(52 * d, pill.height(), 1f, "the bar's height")
        assertTrue(pill.width() < 170 * d, "just the grip, ☰ and Voice: ${pill.width() / d} dp")
        assertEquals(activity.resources.displayMetrics.heightPixels, dock.height, "the whole screen, see-through, so it can go anywhere")
        assertTrue(pill.contains(at(DockView.Act.MIC).x, at(DockView.Act.MIC).y) && pill.contains(at(DockView.Act.MORE).x, at(DockView.Act.MORE).y))
        assertTrue(dock.testHas(DockView.Act.GRIP))
        tap(DockView.Act.MORE); relayout()
        assertTrue(dock.testMenu)
        assertEquals(DockView.MenuItem.EXPAND, dock.testMenuItems().first { it == DockView.MenuItem.EXPAND })
        menu(DockView.MenuItem.EXPAND)
        assertFalse(dock.testCollapsed)
        assertTrue(dock.testHas(DockView.Act.ENTER) && dock.testHas(DockView.Act.MORE))
    }

    @Test fun theOpenFloatingPillDragsByItsGripAndParksAtTheBottomAgain() {
        floating()
        val grip = at(DockView.Act.GRIP)
        val before = assertNotNull(dock.testItem(DockView.Act.MIC))
        touch(MotionEvent.ACTION_DOWN, grip)
        touch(MotionEvent.ACTION_MOVE, PointF(grip.x + 10 * d, grip.y - 300 * d))
        touch(MotionEvent.ACTION_MOVE, PointF(grip.x + 20 * d, grip.y - 500 * d))
        touch(MotionEvent.ACTION_UP, PointF(grip.x + 20 * d, grip.y - 500 * d)); idle(20)
        relayout()
        val after = assertNotNull(dock.testItem(DockView.Act.MIC))
        assertEquals(before.centerY() - 500 * d, after.centerY(), 3 * d, "moved up with the finger")
        assertEquals(before.centerX() + 20 * d, after.centerX(), 3 * d, "sideways too, as far as the screen allows")
        assertFalse(Prefs.get(activity).getBoolean(Prefs.FLOAT_PARKED, true), "floating over the app now")
        val (content, touch) = assertNotNull(dock.floatingInsets())
        assertEquals(dock.height, content, "the app keeps the whole screen")
        assertTrue(touch.single().contains(after.centerX(), after.centerY()))
        assertEquals(emptyList(), talk, "a drag isn't a tap")
        // the keys still work up there
        tap(DockView.Act.MIC)
        assertEquals(listOf("start"), talk)
        tap(DockView.Act.MIC); idle(600); relayout()
        // dropped near the bottom: parked again
        val g2 = at(DockView.Act.GRIP)
        touch(MotionEvent.ACTION_DOWN, g2)
        touch(MotionEvent.ACTION_MOVE, PointF(g2.x, g2.y + 300 * d))
        touch(MotionEvent.ACTION_MOVE, PointF(g2.x, g2.y + 480 * d))
        touch(MotionEvent.ACTION_UP, PointF(g2.x, g2.y + 480 * d)); idle(20)
        relayout()
        assertTrue(Prefs.get(activity).getBoolean(Prefs.FLOAT_PARKED, false))
        val (c2, t2) = assertNotNull(dock.floatingInsets())
        assertTrue(c2 < t2.single().top, "the app above it again")
        // a tap on a key isn't a drag
        tap(DockView.Act.ENTER)
        assertEquals<List<Any>>(listOf(Bar.Key.DELETE), keys)
    }

    private fun upright() {
        floating()
        menu(DockView.MenuItem.UPRIGHT)
        relayout()
    }

    @Test fun turnedUprightItsAColumnAgainstAnEdgeOverTheApp() {
        assertFalse(DockView.MenuItem.UPRIGHT in dock.testMenuItems().also { tap(DockView.Act.MORE); relayout() }.let { dock.testMenuItems() }, "not on the docked bar")
        tap(DockView.Act.MORE); relayout()
        upright()
        assertTrue(dock.testUpright)
        assertTrue(Prefs.get(activity).getBoolean(Prefs.BAR_UPRIGHT, false), "remembered")
        val order = listOf(DockView.Act.GRIP, DockView.Act.MORE, DockView.Act.CURSOR, DockView.Act.KEYBOARD, DockView.Act.MIC, DockView.Act.ENTER)
        val rects = order.map { assertNotNull(dock.testItem(it), "$it") }
        rects.zipWithNext().forEach { (a, b) -> assertTrue(a.bottom < b.top && abs(a.centerX() - b.centerX()) < 1f, "stacked: ⠿ ☰ Cursor ABC Voice own key") }
        rects.drop(1).forEach { assertEquals(it.width(), it.height(), 0.5f, "round keys") }
        assertTrue(rects[1].right > dock.width - 70 * d, "against the right edge")
        val (content, touch) = assertNotNull(dock.floatingInsets())
        assertEquals(dock.height, content, "the app keeps the whole screen")
        assertTrue(touch.first().width() < 70 * d, "only the column takes touches")
        tap(DockView.Act.MORE); relayout()
        assertTrue(DockView.MenuItem.SIDEWAYS in dock.testMenuItems())
        val paste = assertNotNull(dock.testMenuAt(DockView.MenuItem.PASTE))
        assertTrue(paste.x < rects[1].left, "the menu opens beside it, toward the middle")
        menu(DockView.MenuItem.SIDEWAYS)
        assertFalse(dock.testUpright)
        assertTrue(dock.testHas(DockView.Act.GRIP) && dock.testItem(DockView.Act.MORE)!!.width() > 0f)
    }

    @Test fun uprightTheRecordingMicStretchesUpToSendAndDownToCancel() {
        upright()
        tap(DockView.Act.MIC)
        relayout()
        assertTrue(dock.testTalking)
        val mic = at(DockView.Act.MIC)
        assertEquals(dock.testItem(DockView.Act.MIC)!!.width(), dock.testItem(DockView.Act.MIC)!!.height(), 0.5f, "still round: no wide pill")
        // a short pull springs back
        touch(MotionEvent.ACTION_DOWN, mic)
        touch(MotionEvent.ACTION_MOVE, PointF(mic.x, mic.y - 25 * d))
        touch(MotionEvent.ACTION_UP, PointF(mic.x, mic.y - 25 * d)); idle(20)
        assertTrue(dock.testTalking, "short of full stretch: nothing")
        // up to full stretch: sent
        touch(MotionEvent.ACTION_DOWN, mic)
        touch(MotionEvent.ACTION_MOVE, PointF(mic.x, mic.y - 30 * d))
        touch(MotionEvent.ACTION_MOVE, PointF(mic.x, mic.y - 70 * d))
        touch(MotionEvent.ACTION_UP, PointF(mic.x, mic.y - 70 * d)); idle(20)
        assertEquals(listOf("start", "polish", "send"), talk)
        // down to full stretch: thrown away
        dock.hideVoice(); idle(600); relayout()
        tap(DockView.Act.MIC); relayout()
        val m2 = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, m2)
        touch(MotionEvent.ACTION_MOVE, PointF(m2.x, m2.y + 30 * d))
        touch(MotionEvent.ACTION_MOVE, PointF(m2.x, m2.y + 70 * d))
        touch(MotionEvent.ACTION_UP, PointF(m2.x, m2.y + 70 * d)); idle(20)
        assertEquals("cancel", talk.last())
        // a tap finishes and cleans up
        dock.hideVoice(); idle(600); relayout()
        tap(DockView.Act.MIC); relayout()
        tap(DockView.Act.MIC)
        assertEquals("polish", talk.last())
    }

    @Test fun uprightDroppedNearAnEdgeItSnapsAgainstIt() {
        upright()
        val grip = at(DockView.Act.GRIP)
        touch(MotionEvent.ACTION_DOWN, grip)
        touch(MotionEvent.ACTION_MOVE, PointF(grip.x - 120 * d, grip.y - 100 * d))
        touch(MotionEvent.ACTION_MOVE, PointF(grip.x - 320 * d, grip.y - 200 * d))
        touch(MotionEvent.ACTION_UP, PointF(grip.x - 320 * d, grip.y - 200 * d)); idle(20)
        relayout()
        assertTrue(dock.testItem(DockView.Act.MORE)!!.left < 70 * d, "against the left edge now")
        assertEquals(0f, Prefs.get(activity).getFloat(Prefs.FLOAT_X, 0.5f))
        assertEquals(grip.y - 200 * d, at(DockView.Act.GRIP).y, 3 * d, "where it was dropped, up and down")
    }

    @Test fun uprightItStaysWhereItsDroppedAwayFromTheEdges() {
        upright()
        val grip = at(DockView.Act.GRIP)
        // to the middle of the screen: it stays there
        val mid = dock.width / 2f
        touch(MotionEvent.ACTION_DOWN, grip)
        touch(MotionEvent.ACTION_MOVE, PointF((grip.x + mid) / 2, grip.y - 100 * d))
        touch(MotionEvent.ACTION_MOVE, PointF(mid, grip.y - 200 * d))
        touch(MotionEvent.ACTION_UP, PointF(mid, grip.y - 200 * d)); idle(20)
        relayout()
        assertEquals(mid, at(DockView.Act.GRIP).x, 3 * d, "left in the middle, not pushed to an edge")
        // nudged to within the snap distance of the right edge: it snaps against it
        val g2 = at(DockView.Act.GRIP)
        val nearRight = dock.width - 8 * d - 28 * d - 30 * d
        touch(MotionEvent.ACTION_DOWN, g2)
        touch(MotionEvent.ACTION_MOVE, PointF((g2.x + nearRight) / 2, g2.y))
        touch(MotionEvent.ACTION_MOVE, PointF(nearRight, g2.y))
        touch(MotionEvent.ACTION_UP, PointF(nearRight, g2.y)); idle(20)
        relayout()
        assertEquals(1f, Prefs.get(activity).getFloat(Prefs.FLOAT_X, 0.5f))
        assertTrue(dock.testItem(DockView.Act.MORE)!!.right > dock.width - 16 * d, "against the right edge")
    }

    @Test fun theCollapsedPillDragsAnywhereStaysOnScreenAndIsRemembered() {
        floating(collapsed = true)
        val before = assertNotNull(dock.testCollapsedPill())
        val from = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, from)
        touch(MotionEvent.ACTION_MOVE, PointF(from.x - 100 * d, from.y - 300 * d))
        touch(MotionEvent.ACTION_MOVE, PointF(from.x - 200 * d, from.y - 400 * d))
        touch(MotionEvent.ACTION_UP, PointF(from.x - 200 * d, from.y - 400 * d)); idle(20)
        relayout()
        val after = assertNotNull(dock.testCollapsedPill())
        assertEquals(before.centerX() - 200 * d, after.centerX(), 2 * d)
        assertEquals(before.centerY() - 400 * d, after.centerY(), 2 * d)
        assertEquals(emptyList(), talk, "a drag isn't a tap: no dictation")
        // flung past the edge: kept on screen
        val p2 = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, p2)
        touch(MotionEvent.ACTION_MOVE, PointF(p2.x + 2000 * d, p2.y + 3000 * d))
        touch(MotionEvent.ACTION_UP, PointF(p2.x + 2000 * d, p2.y + 3000 * d)); idle(20)
        relayout()
        val edge = assertNotNull(dock.testCollapsedPill())
        assertTrue(edge.right <= dock.width && edge.bottom <= dock.height && edge.left >= 0 && edge.top >= 0, "on screen: $edge")
        // and it comes back where it was left
        val again = DockView(activity, null)
        activity.setContentView(again, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        again.testSetBottomInset(0) // as the test's own view
        again.reloadSettings()
        idle(50)
        again.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
        again.layout(0, 0, dock.width, again.measuredHeight)
        val back = assertNotNull(again.testCollapsedPill())
        assertEquals(edge.centerX(), back.centerX(), 2 * d)
        assertEquals(edge.centerY(), back.centerY(), 2 * d)
    }

    @Test fun recordingCollapsedGrowsThePillAndShrinksItBack() {
        floating(collapsed = true)
        val small = assertNotNull(dock.testCollapsedPill())
        tap(DockView.Act.MIC)
        relayout()
        assertEquals(listOf("start"), talk)
        val wide = assertNotNull(dock.testCollapsedPill())
        assertTrue(wide.width() > small.width() * 2, "the recording pill: ${wide.width() / d} dp")
        assertTrue(wide.left >= 0 && wide.right <= dock.width, "on screen")
        assertEquals(small.centerY(), wide.centerY(), 1f, "in place")
        assertFalse(dock.testHas(DockView.Act.MORE), "no ☰ while recording")
        tap(DockView.Act.MIC)
        idle(600)
        relayout()
        assertEquals(listOf("start", "polish"), talk)
        assertTrue(dock.testCollapsed)
    }

    @Test fun neverSitsUnderTheNavigationBarEvenWhenInsetsReadZero() {
        val fresh = DockView(activity, null)
        activity.setContentView(fresh, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        idle(50)
        fresh.measure(View.MeasureSpec.makeMeasureSpec((400 * d).toInt(), View.MeasureSpec.EXACTLY), 0)
        val id = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val floor = activity.resources.getDimensionPixelSize(id)
        assertTrue(floor > 0)
        assertTrue(fresh.measuredHeight >= (52 * d).toInt() + floor, "bar lifted above the system navigation: ${fresh.measuredHeight}")
    }

    @Test fun tappingTheVoicePillFinishesAndPolishes() {
        tap(DockView.Act.MIC)
        assertEquals(listOf("start"), talk)
        assertTrue(dock.testTalking)
        assertFalse(dock.testHas(DockView.Act.FINISH), "no ✓: the pill itself finishes")
        assertTrue(dock.testHas(DockView.Act.CURSOR) && dock.testHas(DockView.Act.KEYBOARD), "Cursor and ABC stay to hand")
        assertFalse(dock.testHas(DockView.Act.MORE), "⋮ is pushed off the bar")
        tap(DockView.Act.MIC)
        assertEquals(listOf("start", "polish"), talk)
        tap(DockView.Act.MIC)
        swipe(-(dock.testSwipeMark() + 10f))
        assertEquals(listOf("start", "polish", "start", "cancel"), talk)
    }

    @Test fun aPauseCleansUpHalfwayAndTheSilenceTimeoutStillEnds() {
        tap(DockView.Act.MIC)            // the default timeout is 10 s: cleanup at 5 s, finish at 10 s
        idle(4_800)
        assertEquals(listOf("start"), talk)
        idle(400)
        assertEquals(listOf("start", "quiet"), talk)
        dock.heardSpeech()               // talking again: a new quiet stretch, a new cleanup
        idle(5_200)
        assertEquals(listOf("start", "quiet", "quiet"), talk)
        idle(5_000)
        assertEquals(listOf("start", "quiet", "quiet", "end"), talk)
    }

    @Test fun whileTalkingCursorAndAbcShrinkToTheLeftAndPushMoreOff() {
        dock.canPaste = true
        dock.clipPreview = "Meet at the trailhead"
        relayout()
        val more = dock.testItem(DockView.Act.MORE)!!
        val bksp = dock.testItem(DockView.Act.BACKSPACE)!!
        val voice = dock.testItem(DockView.Act.MIC)!!
        tap(DockView.Act.MIC)
        relayout()
        assertFalse(dock.testHas(DockView.Act.MORE), "⋮ is pushed off the bar")
        assertEquals(more, dock.testItem(DockView.Act.CURSOR), "Cursor takes ⋮'s place, as an icon")
        val abc = dock.testItem(DockView.Act.KEYBOARD)!!
        assertEquals(more.width(), abc.width(), 1f)
        assertEquals(bksp, dock.testItem(DockView.Act.BACKSPACE), "⌫ stays put")
        val pill = dock.testItem(DockView.Act.MIC)!!
        assertTrue(pill.left > abc.right); assertEquals(voice.right, pill.right, 1f)
        assertFalse(dock.testHas(DockView.Act.TALK_PASTE) || dock.testHas(DockView.Act.DISCARD))
    }

    @Test fun withSevenButtonsCursorAndAbcTakeTheFirstTwoPlacesWhileDictating() {
        Prefs.get(activity).edit().commit()
        dock.reloadSettings()
        relayout()
        val more = dock.testItem(DockView.Act.MORE)!!
        val own = dock.testItem(DockView.Act.SPACE_ALT)!!
        val voice = dock.testItem(DockView.Act.MIC)!!
        val bksp = dock.testItem(DockView.Act.BACKSPACE)!!
        val enter = dock.testItem(DockView.Act.ENTER)!!
        tap(DockView.Act.MIC)
        relayout()
        assertEquals(more, dock.testItem(DockView.Act.CURSOR), "Cursor slides into ⋮'s place")
        assertEquals(own, dock.testItem(DockView.Act.KEYBOARD), "ABC into the own key's")
        assertFalse(dock.testHas(DockView.Act.MORE) || dock.testHas(DockView.Act.SPACE_ALT), "those are pushed off the bar")
        val pill = dock.testItem(DockView.Act.MIC)!!
        assertEquals(own.right + 5 * d, pill.left, 1f); assertEquals(voice.right, pill.right, 1f)
        assertEquals(bksp, dock.testItem(DockView.Act.BACKSPACE))
        assertEquals(enter, dock.testItem(DockView.Act.ENTER))
        tap(DockView.Act.ENTER)
        assertEquals<List<Any>>(listOf(Bar.Key.ENTER), keys)
        assertTrue(dock.testTalking, "keys don't end the dictation")
    }

    @Test fun anOwnKeySetToFixListensForWhatToFix() {
        sevenButtons("fix_last")
        // no dictation needed: the service fixes whatever's in the box (and says so when there's nothing)
        tap(DockView.Act.SPACE_ALT)
        relayout()
        assertEquals(listOf("fix"), talk)
        assertTrue(dock.testTalking && dock.testFixMode)
        // a swipe right finishes it, but there's nothing to send
        val pill = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, pill)
        touch(MotionEvent.ACTION_MOVE, PointF(pill.x + dock.testSwipeMark() + 20 * d, pill.y))
        touch(MotionEvent.ACTION_UP, PointF(pill.x + dock.testSwipeMark() + 20 * d, pill.y)); idle(20)
        assertEquals(listOf("fix", "polish"), talk)
        assertFalse(dock.testFixMode)
    }

    @Test fun holdingVoiceGoesStraightIntoAFix() {
        val v = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, v); idle(300)
        assertEquals(emptyList(), talk, "not yet: a press is still a press")
        idle(200)
        assertEquals(listOf("fix"), talk, "held: listening for what to fix")
        assertTrue(dock.testFixMode)
        touch(MotionEvent.ACTION_UP, v); idle(20)
        assertTrue(dock.testTalking && dock.testFixMode, "letting go doesn't end it")
        // a plain tap still dictates
        dock.reset()
        talk.clear()
        relayout()
        tap(DockView.Act.MIC)
        assertEquals(listOf("start"), talk)
        assertFalse(dock.testFixMode)
    }

    @Test fun theScreenStaysOnWhileListening() {
        assertFalse(dock.keepScreenOn)
        tap(DockView.Act.MIC)
        assertTrue(dock.keepScreenOn, "a long dictation isn't cut off by the screen sleeping")
        relayout()
        tap(DockView.Act.MIC)
        assertFalse(dock.keepScreenOn)
    }

    @Test fun thePillSaysConnectingUntilTheRecognizerIsReadyOrWordsArrive() {
        tap(DockView.Act.MIC)
        dock.showListening()
        assertTrue(dock.testConnecting)
        dock.showReady()
        assertFalse(dock.testConnecting)
        // no ready signal, but words came back: it's hearing
        dock.showListening()
        assertTrue(dock.testConnecting)
        dock.heardSpeech()
        assertFalse(dock.testConnecting)
    }

    @Test fun theKeyboardInTheFirstFourSecondsDropsWhatWasHeard() {
        tap(DockView.Act.MIC)
        relayout()
        idle(1_000)
        tap(DockView.Act.KEYBOARD)
        relayout()
        // thrown away (no cleanup, nothing kept), and the keyboard is up with nothing to come back to
        assertEquals(listOf("start", "cancel", "keys open"), talk)
        assertFalse(dock.testTalking)
        assertEquals(DockView.Drawer.KEYS, dock.drawer)
        pullKeysDown()
        relayout()
        assertFalse(dock.testTalking, "no listening to resume")
        // the same for Gboard (a hold, by default)
        tap(DockView.Act.MIC)
        relayout()
        touch(MotionEvent.ACTION_DOWN, at(DockView.Act.KEYBOARD)); idle(500); touch(MotionEvent.ACTION_UP, at(DockView.Act.KEYBOARD)); idle(20)
        assertEquals(listOf("start", "cancel", "keys open", "keys closed", "start", "cancel"), talk)
        assertEquals(1, toGboard)
    }

    @Test fun pausingForTheKeyboardKeepsThePolishingPillAndBarOutOfItsWay() {
        tap(DockView.Act.MIC)
        relayout()
        idle(4_100) // past the first moments, so it's a pause
        tap(DockView.Act.KEYBOARD)
        dock.showCleaning("raw words") // the service polishing what was said
        relayout()
        assertEquals(DockView.Drawer.KEYS, dock.drawer)
        assertEquals(Bar.Voice.CLEANING, dock.voice, "what was said is still being polished")
        for (a in listOf(DockView.Act.MIC, DockView.Act.CURSOR, DockView.Act.KEYBOARD, DockView.Act.BACKSPACE, DockView.Act.MORE))
            assertFalse(dock.testHas(a), "$a gives way to the keyboard")
    }

    @Test fun cursorOrAbcPausesTheDictationAndClosingThemListensAgain() {
        tap(DockView.Act.MIC)
        relayout()
        tap(DockView.Act.CURSOR)
        relayout()
        assertEquals(listOf("start", "polish"), talk, "what was said is finished, to work on")
        assertEquals(DockView.Drawer.PAD, dock.drawer)
        assertFalse(dock.testTalking)
        // Done on the pad: listening again
        tap(DockView.Act.CURSOR)
        relayout()
        assertEquals(DockView.Drawer.NONE, dock.drawer)
        assertEquals(listOf("start", "polish", "start"), talk)
        assertTrue(dock.testTalking)
        // ABC: the keyboard; a trip to its menu and back keeps the pause, and pulling it down brings listening back
        idle(4_100)
        tap(DockView.Act.KEYBOARD)
        relayout()
        assertEquals(DockView.Drawer.KEYS, dock.drawer)
        val menu = keysMenuKey()
        touch(MotionEvent.ACTION_DOWN, menu); touch(MotionEvent.ACTION_UP, menu); idle(20)
        relayout()
        assertTrue(dock.testMenu)
        menu(DockView.MenuItem.DICTATIONS)
        assertEquals(DockView.Drawer.KEYS, dock.drawer, "the dictations open in the menu, over the keyboard")
        assertFalse(dock.testTalking)
        touch(MotionEvent.ACTION_DOWN, PointF(dock.width - 4f, 2f)); touch(MotionEvent.ACTION_UP, PointF(dock.width - 4f, 2f)); idle(20)
        relayout()
        assertFalse(dock.testMenu)
        assertEquals(DockView.Drawer.KEYS, dock.drawer)
        assertFalse(dock.testTalking)
        pullKeysDown()
        relayout()
        assertTrue(dock.testTalking)
        assertEquals(listOf("start", "polish", "start", "polish", "keys open", "keys closed", "start"), talk, "the menu over the keyboard leaves it up")
        // paused for the keyboard, then off to Gboard: no listening to come back to
        idle(4_100)
        tap(DockView.Act.KEYBOARD)
        relayout()
        val vb2 = keysMenuKey()
        touch(MotionEvent.ACTION_DOWN, vb2); idle(500); touch(MotionEvent.ACTION_UP, vb2); idle(200)
        relayout()
        assertEquals(1, toGboard)
        assertFalse(dock.testTalking)
    }

    @Test fun theSevenButtonBarsOwnKeyDoesWhatItsSetToAndReturnAndDeleteStayPut() {
        Prefs.get(activity).edit().commit()
        dock.reloadSettings()
        relayout()
        val enter = dock.testItem(DockView.Act.ENTER)!!
        val bksp = dock.testItem(DockView.Act.BACKSPACE)!!
        for (key in Bar.ExtraKey.entries) {
            Prefs.get(activity).edit().putString(Prefs.BAR_KEY, key.pref).commit()
            dock.reloadSettings()
            relayout()
            assertEquals(enter, dock.testItem(DockView.Act.ENTER), "↵ stays at the right end with $key")
            assertEquals(bksp, dock.testItem(DockView.Act.BACKSPACE), "⌫ stays next to it with $key")
            tap(DockView.Act.SPACE_ALT)
            if (key == Bar.ExtraKey.FIX_LAST) dock.reset() // it listens for what to fix: back to rest for the next one
        }
        assertEquals<List<Any>>(listOf(Bar.Key.SPACE, Bar.Key.DELETE, Bar.Key.ENTER, Bar.Key.UNDO), keys)
        assertEquals(listOf(Bar.EditAction.PASTE, Bar.EditAction.SELECT_ALL), edits)
        assertEquals(listOf("repeat"), dictationActions)
        assertEquals(Prefs.BAR_STYLE_FLOATING, Prefs.get(activity).getString(Prefs.BAR_STYLE, null), "and set to Docked ↔ floating, it floats the bar")
    }

    @Test fun theMenuKeyIsAlwaysTheMenuAndTheOtherThreeAreOwnKeys() {
        sevenButtons()
        // ☰ opens the list and closes it; a hold doesn't offer choices
        tap(DockView.Act.MORE)
        assertTrue(dock.testMenu)
        relayout()
        tap(DockView.Act.MORE)
        assertFalse(dock.testMenu)
        val more = at(DockView.Act.MORE)
        touch(MotionEvent.ACTION_DOWN, more); idle(400)
        assertFalse(dock.testPicker, "☰ isn't an own key")
        touch(MotionEvent.ACTION_UP, more); idle(20)
        if (dock.testMenu) { tap(DockView.Act.MORE) }
        relayout()
        tap(DockView.Act.ENTER)
        assertEquals<List<Any>>(listOf(Bar.Key.ENTER), keys)
        // ↵ held: its choices line up with its right edge; pick Undo
        val ret = at(DockView.Act.ENTER)
        touch(MotionEvent.ACTION_DOWN, ret)
        idle(400)
        relayout()
        val undo = assertNotNull(dock.testPickerRow(Bar.ExtraKey.UNDO))
        assertTrue(undo.x <= ret.x + 1, "the column ends at the key's right edge")
        touch(MotionEvent.ACTION_MOVE, undo); touch(MotionEvent.ACTION_UP, undo); idle(20)
        assertEquals("undo", Prefs.get(activity).getString(Prefs.BAR_KEY_ENTER, null))
        relayout()
        tap(DockView.Act.ENTER)
        assertEquals(Bar.Key.UNDO, keys.last())
    }

    @Test fun aStyleKeyFlipsBetweenDockedAndFloating() {
        Prefs.get(activity).edit().putString(Prefs.BAR_KEY_ENTER, "style").putString(Prefs.BAR_KEY_FLOAT, "style").commit()
        dock.reloadSettings()
        relayout()
        tap(DockView.Act.ENTER)
        relayout()
        assertEquals(Prefs.BAR_STYLE_FLOATING, Prefs.get(activity).getString(Prefs.BAR_STYLE, null))
        assertFalse(dock.testHas(DockView.Act.SPACE_ALT), "the floating pill")
        tap(DockView.Act.ENTER)
        relayout()
        assertEquals(Prefs.BAR_STYLE_DOCKED, Prefs.get(activity).getString(Prefs.BAR_STYLE, null))
        assertTrue(dock.testHas(DockView.Act.SPACE_ALT), "docked again")
        // the menu does it too
        menu(DockView.MenuItem.FLOAT)
        assertEquals(Prefs.BAR_STYLE_FLOATING, Prefs.get(activity).getString(Prefs.BAR_STYLE, null))
        menu(DockView.MenuItem.DOCK)
        assertEquals(Prefs.BAR_STYLE_DOCKED, Prefs.get(activity).getString(Prefs.BAR_STYLE, null))
    }

    @Test fun onlyTheSpaceKeySlidesTheCursor() {
        Prefs.get(activity).edit().putString(Prefs.BAR_KEY, "paste").commit()
        dock.reloadSettings()
        relayout()
        val p = at(DockView.Act.SPACE_ALT)
        touch(MotionEvent.ACTION_DOWN, p)
        touch(MotionEvent.ACTION_MOVE, PointF(p.x + 80 * d, p.y))
        touch(MotionEvent.ACTION_UP, PointF(p.x + 80 * d, p.y))
        idle(20)
        assertFalse(keys.contains(Bar.Key.RIGHT), "a slide on the paste key doesn't move the cursor")
    }

    private fun sevenButtons(key: String = "space") {
        Prefs.get(activity).edit().putString(Prefs.BAR_KEY, key).commit()
        dock.reloadSettings()
        relayout()
    }

    @Test fun holdingTheOwnKeyThenSlidingUpSetsAChoiceWithoutRunningIt() {
        sevenButtons()
        val key = at(DockView.Act.SPACE_ALT)
        touch(MotionEvent.ACTION_DOWN, key)
        idle(400)
        assertTrue(dock.testPicker, "a hold opens the choices")
        relayout()
        val paste = assertNotNull(dock.testPickerRow(Bar.ExtraKey.PASTE))
        touch(MotionEvent.ACTION_MOVE, paste)
        touch(MotionEvent.ACTION_UP, paste)
        idle(20)
        assertFalse(dock.testPicker)
        assertEquals(emptyList(), edits, "picking only sets it: nothing pasted")
        assertTrue(keys.isEmpty(), "and the hold itself typed no space")
        assertEquals("paste", Prefs.get(activity).getString(Prefs.BAR_KEY, null), "it's the key now")
        relayout()
        tap(DockView.Act.SPACE_ALT)
        assertEquals(listOf(Bar.EditAction.PASTE), edits, "a press uses it")
    }

    @Test fun aSwipeUpOpensTheChoicesAtOnce() {
        sevenButtons()
        val key = at(DockView.Act.SPACE_ALT)
        touch(MotionEvent.ACTION_DOWN, key)
        touch(MotionEvent.ACTION_MOVE, PointF(key.x, key.y - 40 * d))
        assertTrue(dock.testPicker)
        relayout()
        val undo = dock.testPickerRow(Bar.ExtraKey.UNDO)!!
        touch(MotionEvent.ACTION_MOVE, undo)
        touch(MotionEvent.ACTION_UP, undo)
        idle(20)
        assertEquals("undo", Prefs.get(activity).getString(Prefs.BAR_KEY, null))
        assertTrue(keys.isEmpty(), "set, not run")
    }

    @Test fun lettingGoFirstLeavesTheChoicesUpForATapAndATapElsewhereClosesThem() {
        sevenButtons()
        val key = at(DockView.Act.SPACE_ALT)
        touch(MotionEvent.ACTION_DOWN, key); idle(400); touch(MotionEvent.ACTION_UP, key); idle(20)
        assertTrue(dock.testPicker, "still up after letting go")
        assertTrue(keys.isEmpty())
        relayout()
        val last = dock.testPickerRow(Bar.ExtraKey.LAST_DICTATION)!!
        touch(MotionEvent.ACTION_DOWN, last); touch(MotionEvent.ACTION_UP, last); idle(20)
        assertFalse(dock.testPicker)
        assertEquals(emptyList(), dictationActions, "a tap on a choice sets it too, without running it")
        assertEquals("last_dictation", Prefs.get(activity).getString(Prefs.BAR_KEY, null))
        // open again, then tap the bar's ↵: closes, and ↵ isn't pressed
        relayout()
        val k2 = at(DockView.Act.SPACE_ALT)
        touch(MotionEvent.ACTION_DOWN, k2); idle(400); touch(MotionEvent.ACTION_UP, k2); idle(20)
        relayout()
        val enter = at(DockView.Act.ENTER)
        touch(MotionEvent.ACTION_DOWN, enter); touch(MotionEvent.ACTION_UP, enter); idle(20)
        assertFalse(dock.testPicker)
        assertTrue(keys.isEmpty(), "the tap that closes it does nothing else")
    }

    @Test fun slidingTheSpaceKeySidewaysStillMovesTheCursorAndOpensNothing() {
        sevenButtons()
        val key = at(DockView.Act.SPACE_ALT)
        touch(MotionEvent.ACTION_DOWN, key)
        touch(MotionEvent.ACTION_MOVE, PointF(key.x + 60 * d, key.y))
        idle(500)
        touch(MotionEvent.ACTION_UP, PointF(key.x + 60 * d, key.y))
        idle(20)
        assertFalse(dock.testPicker)
        assertTrue(keys.contains(Bar.Key.RIGHT))
    }

    @Test fun noClipboardNoPasteButton() {
        dock.canPaste = false
        tap(DockView.Act.MIC)
        relayout()
        assertFalse(dock.testHas(DockView.Act.TALK_PASTE))
    }

    @Test fun theMenuIsAListWithEditKeysAndPasteFromTheClipboard() {
        tap(DockView.Act.MORE)
        relayout()
        assertTrue(dock.testMenu)
        assertEquals(DockView.Drawer.NONE, dock.drawer, "a list, not the old panel")
        assertEquals(listOf(DockView.MenuItem.PASTE, DockView.MenuItem.DICTATIONS, DockView.MenuItem.FLOAT, DockView.MenuItem.SWITCH, DockView.MenuItem.SETTINGS), dock.testMenuItems())
        // the edit keys leave it open; cut and copy want a selection
        menu(DockView.MenuEdit.COPY)
        assertEquals(emptyList(), edits, "nothing selected: nothing to copy")
        menu(DockView.MenuEdit.SELECT_ALL)
        dock.hasSelection = true
        menu(DockView.MenuEdit.COPY)
        menu(DockView.MenuEdit.UNDO)
        assertEquals(listOf(Bar.EditAction.SELECT_ALL, Bar.EditAction.COPY), edits)
        assertEquals<List<Any>>(listOf(Bar.Key.UNDO), keys)
        assertTrue(dock.testMenu, "still open")
        // Paste: whatever's on the clipboard, copied anywhere; then the list closes
        dock.canPaste = false
        menu(DockView.MenuItem.PASTE)
        assertEquals(2, edits.size, "nothing to paste")
        dock.canPaste = true
        dock.clipPreview = "Meet at the trailhead"
        menu(DockView.MenuItem.PASTE)
        assertEquals(Bar.EditAction.PASTE, edits.last())
        assertFalse(dock.testMenu)
        // a tap anywhere else closes it
        tap(DockView.Act.MORE); relayout()
        assertTrue(dock.testMenu)
        val far = PointF(dock.width - 4f, at(DockView.Act.MIC).y)
        touch(MotionEvent.ACTION_DOWN, far); touch(MotionEvent.ACTION_UP, far); idle(20)
        assertFalse(dock.testMenu)
        assertEquals(emptyList(), talk, "and that tap did nothing else")
    }

    @Test fun recentDictationsOpenInsideTheMenuAndATapPastesOneAsTyped() {
        dock.dictations = (1..7).map { DictationHistory.Entry(it.toLong(), "heard $it", "Typed $it.") }.reversed()
        menu(DockView.MenuItem.DICTATIONS)
        assertTrue(dock.testMenu, "the menu stays up: the same card, now the dictations")
        assertEquals(DockView.MenuPage.DICTATIONS, dock.testMenuPage)
        assertEquals(DockView.Drawer.NONE, dock.drawer, "no panel")
        for (t in 7L downTo 3L) assertNotNull(dock.testMenuAt(DockView.DictRow(t)), "dictation $t is listed")
        assertNull(dock.testMenuAt(DockView.DictRow(2)), "just the newest five")
        // ‹ goes back to the menu
        menu(DockView.MenuBack)
        assertEquals(DockView.MenuPage.MAIN, dock.testMenuPage)
        menu(DockView.MenuItem.DICTATIONS)
        menu(DockView.DictRow(5))
        assertEquals(listOf("PASTE_TYPED 5"), dictationActions)
        assertFalse(dock.testMenu)
    }

    @Test fun holdingADictationOffersAsSaidCleanUpAndDelete() {
        dock.dictations = listOf(DictationHistory.Entry(42, "um so tell shauna yes", "Tell Siona yes."), DictationHistory.Entry(41, "older", "Older."))
        menu(DockView.MenuItem.DICTATIONS)
        val row = assertNotNull(dock.testMenuAt(DockView.DictRow(42)))
        assertNull(dock.testMenuAt(DockView.DictOpt(42, Bar.DictationAction.RECLEAN)), "no options until it's held")
        touch(MotionEvent.ACTION_DOWN, row); idle(600); touch(MotionEvent.ACTION_UP, row); idle(20)
        relayout()
        assertEquals(42L, dock.testMenuHeld)
        assertEquals(emptyList(), dictationActions, "a hold doesn't paste it")
        val opts = listOf(Bar.DictationAction.PASTE_HEARD, Bar.DictationAction.RECLEAN, Bar.DictationAction.DELETE).map { assertNotNull(dock.testMenuAt(DockView.DictOpt(42, it))) }
        assertTrue(opts.all { it.y > row.y + 20 * d }, "under the row, with room")
        // clean up: again and copied, with the list left up to watch
        menu(DockView.DictOpt(42, Bar.DictationAction.RECLEAN))
        assertEquals(listOf("RECLEAN 42"), dictationActions)
        assertTrue(dock.testMenu)
        assertNull(dock.testMenuHeld)
        // while one is being cleaned, Clean up waits
        dock.recleaning = 42
        val r2 = assertNotNull(dock.testMenuAt(DockView.DictRow(42)))
        touch(MotionEvent.ACTION_DOWN, r2); idle(600); touch(MotionEvent.ACTION_UP, r2); idle(20); relayout()
        menu(DockView.DictOpt(42, Bar.DictationAction.RECLEAN))
        assertEquals(listOf("RECLEAN 42"), dictationActions)
        // as said: pasted before cleanup, and the menu closes
        menu(DockView.DictOpt(42, Bar.DictationAction.PASTE_HEARD))
        assertEquals(listOf("RECLEAN 42", "PASTE_HEARD 42"), dictationActions)
        assertFalse(dock.testMenu)
    }

    @Test fun backspaceWorksWhileDictating() {
        tap(DockView.Act.MIC)
        relayout()
        assertFalse(dock.testHas(DockView.Act.DISCARD), "no ✕: swipe left cancels")
        tap(DockView.Act.BACKSPACE)
        assertEquals<List<Any>>(listOf(Bar.Key.DELETE), keys)
        assertTrue(dock.testTalking, "still dictating")
        // held: keeps deleting, and letting go isn't one more
        val p = at(DockView.Act.BACKSPACE)
        touch(MotionEvent.ACTION_DOWN, p)
        idle(1_000)
        touch(MotionEvent.ACTION_UP, p)
        assertTrue(keys.size > 3, "repeats while held: ${keys.size}")
        assertTrue(dock.testTalking)
    }

    @Test fun swipingThePurplePillUpWavesVoiceBarOff() {
        tap(DockView.Act.MIC)
        relayout()
        dock.showCommand("sen", false)
        val p = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, p)
        for (i in 1..6) { touch(MotionEvent.ACTION_MOVE, PointF(p.x + i, p.y - i * 8 * d)); idle(10) }
        touch(MotionEvent.ACTION_UP, PointF(p.x + 6, p.y - 48 * d))
        assertEquals(listOf("start", "dismiss"), talk, "waved off, not finished, not sent")
        assertTrue(dock.testTalking)
    }

    @Test fun anUpSwipeOnTheOrdinaryPillDoesNothing() {
        tap(DockView.Act.MIC)
        relayout()
        val p = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, p)
        for (i in 1..6) { touch(MotionEvent.ACTION_MOVE, PointF(p.x, p.y - i * 8 * d)); idle(10) }
        touch(MotionEvent.ACTION_UP, PointF(p.x, p.y - 48 * d))
        assertFalse(talk.contains("dismiss"))
    }

    @Test fun theCursorPanelHasSelectAll() {
        tap(DockView.Act.CURSOR)
        relayout()
        assertTrue(dock.testHas(DockView.Act.CHIP_SELECT_ALL))
        tap(DockView.Act.CHIP_SELECT_ALL)
        assertEquals(listOf(Bar.EditAction.SELECT_ALL), edits)
    }

    @Test fun theHintShowsAtTheStartThenOnlyAfterThreeQuietSecondsEveryTen() {
        tap(DockView.Act.MIC)
        val t0 = android.os.SystemClock.uptimeMillis()
        assertEquals(1f, dock.testHint(t0), "shown as dictation starts")
        assertEquals(0f, dock.testHint(t0 + 2_800), "waveform once it has played")
        idle(2_500); dock.heardSpeech()           // talking: no hint
        val h = android.os.SystemClock.uptimeMillis()
        assertEquals(0f, dock.testHint(h + 2_500), "not before 3 s of quiet")
        assertEquals(1f, dock.testHint(h + 3_000 + 1_000), "flashes after 3 s of quiet")
        assertEquals(0f, dock.testHint(h + 3_000 + 3_000), "then the waveform")
        assertEquals(1f, dock.testHint(h + 13_000 + 1_000), "and again 10 s later if it's still quiet")
    }

    private fun swipe(dx: Float) {
        val p = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, p)
        for (i in 1..8) { touch(MotionEvent.ACTION_MOVE, PointF(p.x + dx * i / 8, p.y)); idle(10) }
        touch(MotionEvent.ACTION_UP, PointF(p.x + dx, p.y))
        idle(20)
    }

    @Test fun swipingTheVoicePillRightFinishesThenSends() {
        tap(DockView.Act.MIC)
        relayout()
        swipe(dock.testSwipeMark() + 10f)
        assertEquals(listOf("start", "polish", "send"), talk)
        assertFalse(dock.testTalking)
    }

    @Test fun swipingTheVoicePillLeftCancels() {
        tap(DockView.Act.MIC)
        relayout()
        swipe(-(dock.testSwipeMark() + 10f))
        assertEquals(listOf("start", "cancel"), talk)
        assertFalse(dock.testTalking)
    }

    @Test fun aShortSwipeSpringsBackAndKeepsListening() {
        tap(DockView.Act.MIC)
        relayout()
        swipe(dock.testSwipeMark() * 0.6f)
        assertEquals(listOf("start"), talk)
        assertTrue(dock.testTalking)
    }

    @Test fun holdingTheVoicePillNoLongerSends() {
        tap(DockView.Act.MIC)
        relayout()
        val p = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, p)
        idle(800)
        touch(MotionEvent.ACTION_UP, p)
        assertEquals(listOf("start", "polish"), talk)
    }

    @Test fun voiceBarTurnsThePillToACommandPreview() {
        tap(DockView.Act.MIC)
        relayout()
        dock.showCommand("send", understood = true)
        assertEquals("send", dock.testCommand)
        tap(DockView.Act.MIC)
        assertEquals(null, dock.testCommand, "finishing puts it back")
    }

    @Test fun polishingStaysInTheRecordingPillsPlace() {
        tap(DockView.Act.MIC)
        relayout()
        val pill = dock.testItem(DockView.Act.MIC)!!
        tap(DockView.Act.MIC)
        dock.showCleaning("raw words")
        relayout()
        val polishing = dock.testItem(DockView.Act.MIC)!!
        assertEquals(pill.left, polishing.left, 1f)
        assertEquals(pill.right, polishing.right, 1f)
        val x = dock.testItem(DockView.Act.BACKSPACE)
        assertTrue(x != null, "⌫ stays, ghosted")
        tap(DockView.Act.BACKSPACE)
        assertEquals(Bar.Voice.CLEANING, dock.voice, "the ghosted ⌫ does nothing")
        assertTrue(keys.none { it == Bar.Key.DELETE })
    }

    @Test fun aMessageWithAnActionRunsItOnTap() {
        var opened = 0
        dock.showMessage("Way back from Gboard is off") { opened++ }
        relayout()
        tap(DockView.Act.MIC)
        assertEquals(1, opened)
        assertEquals(Bar.Voice.OFF, dock.voice)
        assertEquals(emptyList<String>(), talk, "the tap didn't start dictation")
    }

    @Test fun comingBackFromGboardStartsListeningRightAway() {
        dock.startVoice()
        idle(20)
        assertTrue(dock.testTalking)
        assertEquals(listOf("start"), talk)
        dock.startVoice()  // a second request while already listening does nothing
        assertEquals(listOf("start"), talk)
    }

    @Test fun theVoicePillGrowsRatherThanJumping() {
        dock.settle()
        val before = dock.testAnimRect("voice")!!.width()
        tap(DockView.Act.MIC)
        dock.testFrame(); idle(16); dock.testFrame(); idle(16); dock.testFrame()
        val mid = dock.testAnimRect("voice")!!.width()
        dock.settle()
        val after = dock.testAnimRect("voice")!!.width()
        assertTrue(mid > before && mid < after, "mid-way: $before < $mid < $after")
    }

    @Test fun silenceFinishesWithoutForcingPolish() {
        tap(DockView.Act.MIC)
        idle(9_000)
        dock.heardSpeech()
        idle(9_000)
        assertEquals(listOf("start"), talk.filter { it != "quiet" }, "speech restarts the clock")
        idle(1_500)
        assertEquals(listOf("start", "end"), talk.filter { it != "quiet" })
    }

    @Test fun afterFiveQuietSecondsThePillDrains() {
        tap(DockView.Act.MIC)
        idle(4_000)
        assertEquals(1f, dock.testDrain(), "not yet")
        idle(3_000)
        val mid = dock.testDrain()
        assertTrue(mid in 0.3f..0.8f, "$mid")
        dock.heardSpeech()
        assertEquals(1f, dock.testDrain(), "speech refills it")
    }




    private fun useEqualBar() {
        Prefs.get(activity).edit { }
        dock.reloadSettings()
        relayout()
    }

    @Test fun equalBarHasSevenEqualPillsInOrder() {
        useEqualBar()
        val order = listOf(DockView.Act.MORE, DockView.Act.SPACE_ALT, DockView.Act.CURSOR, DockView.Act.KEYBOARD,
            DockView.Act.MIC, DockView.Act.BACKSPACE, DockView.Act.ENTER).map { assertNotNull(dock.testItem(it), "$it is on the bar") }
        for (i in 1 until order.size) {
            assertTrue(order[i - 1].right < order[i].left, "left to right in order")
            assertEquals(order[0].width(), order[i].width(), 0.5f)
        }
    }

    @Test fun equalBarSpaceAndEnterTypeOnATap() {
        useEqualBar()
        tap(DockView.Act.SPACE_ALT)
        tap(DockView.Act.ENTER)
        assertEquals(listOf<Any>(Bar.Key.SPACE, Bar.Key.ENTER), keys)
    }

    @Test fun equalBarBackspaceHoldOpensItsChoicesAndDoubleTapHoldKeepsDeleting() {
        useEqualBar()
        val b = at(DockView.Act.BACKSPACE)
        // a hold: its choices, nothing deleted
        touch(MotionEvent.ACTION_DOWN, b)
        idle(800)
        assertTrue(dock.testPicker, "a hold on ⌫ opens the choices")
        touch(MotionEvent.ACTION_UP, b); idle(20)
        assertEquals(emptyList(), keys)
        touch(MotionEvent.ACTION_DOWN, PointF(b.x, b.y - 400 * d)); touch(MotionEvent.ACTION_UP, PointF(b.x, b.y - 400 * d)); idle(20) // close it
        assertFalse(dock.testPicker)
        // a tap, then press again and hold: keeps deleting
        touch(MotionEvent.ACTION_DOWN, b); touch(MotionEvent.ACTION_UP, b); idle(60)
        touch(MotionEvent.ACTION_DOWN, b)
        idle(800)
        touch(MotionEvent.ACTION_UP, b)
        assertFalse(dock.testPicker)
        assertTrue(keys.size >= 4 && keys.all { it == Bar.Key.DELETE }, "repeated deletes, got $keys")
    }

    @Test fun theKeyRightOfVoiceIsASecondOwnKeyThatStaysWhileDictating() {
        useEqualBar()
        // hold, slide onto Paste, let go: it pastes now and stays Paste
        val b = at(DockView.Act.BACKSPACE)
        touch(MotionEvent.ACTION_DOWN, b)
        idle(400)
        relayout()
        val paste = assertNotNull(dock.testPickerRow(Bar.ExtraKey.PASTE))
        assertTrue(paste.x <= b.x + 1, "the column lines up with the key's right edge, over the bar")
        touch(MotionEvent.ACTION_MOVE, paste); touch(MotionEvent.ACTION_UP, paste); idle(20)
        assertEquals(emptyList(), edits, "set, not run")
        assertEquals("paste", Prefs.get(activity).getString(Prefs.BAR_KEY_RIGHT, null))
        assertEquals("space", Prefs.get(activity).getString(Prefs.BAR_KEY, Prefs.DEFAULT_BAR_KEY), "the left key is untouched")
        // while dictating it's still there, and still Paste
        tap(DockView.Act.MIC)
        relayout()
        assertTrue(dock.testHas(DockView.Act.BACKSPACE))
        tap(DockView.Act.BACKSPACE)
        assertEquals(listOf(Bar.EditAction.PASTE), edits)
        assertEquals(emptyList(), keys, "nothing deleted")
    }

    @Test fun theLeftKeyCanBeBackspaceToo() {
        sevenButtons("backspace")
        val k = at(DockView.Act.SPACE_ALT)
        tap(DockView.Act.SPACE_ALT)
        assertEquals<List<Any>>(listOf(Bar.Key.DELETE), keys)
        // a second press right after, held: keeps deleting
        touch(MotionEvent.ACTION_DOWN, k)
        idle(800)
        touch(MotionEvent.ACTION_UP, k)
        assertTrue(keys.size >= 4 && keys.all { it == Bar.Key.DELETE }, "repeated deletes, got $keys")
    }

    @Test fun aTapOnBackspaceDeletesOnce() {
        tap(DockView.Act.BACKSPACE)
        assertEquals(listOf<Any>(Bar.Key.DELETE), keys)
    }

    @Test fun tapThenHoldBackspaceRapidFires() {
        val b = at(DockView.Act.BACKSPACE)
        tap(DockView.Act.BACKSPACE)
        idle(100)
        touch(MotionEvent.ACTION_DOWN, b)
        idle(1_000)
        assertFalse(dock.testPicker, "no choices on the second press")
        touch(MotionEvent.ACTION_UP, b)
        assertTrue(keys.size > 5 && keys.all { it == Bar.Key.DELETE }, "$keys")
        // a quick double tap is just two deletes
        keys.clear()
        idle(1_000)
        tap(DockView.Act.BACKSPACE); idle(80); tap(DockView.Act.BACKSPACE)
        assertEquals(2, keys.size)
        // and too slow a second press is a fresh hold: its choices
        idle(1_000)
        touch(MotionEvent.ACTION_DOWN, b); idle(400)
        assertTrue(dock.testPicker)
        touch(MotionEvent.ACTION_UP, b)
    }

    @Test fun abcTapOpensOurKeyboardAndAHoldHandsOffToGboard() {
        tap(DockView.Act.KEYBOARD)
        relayout()
        assertEquals(DockView.Drawer.KEYS, dock.drawer)
        assertEquals(listOf("keys open"), talk)
        assertEquals(0, toGboard)
        // typing on it reaches the service
        val k = dock.testKeys.testKey("h")!!
        val p = PointF(k.centerX(), k.centerY() + dock.testBarTop)
        touch(MotionEvent.ACTION_DOWN, p); touch(MotionEvent.ACTION_UP, p); idle(20)
        assertEquals(listOf("h"), typed)
        // it has the bar's row: the bar's keys give way, and its bottom row sits in that row
        assertFalse(dock.testHas(DockView.Act.KEYBOARD) || dock.testHas(DockView.Act.MIC) || dock.testHas(DockView.Act.MORE))
        val menuKey = dock.testKeys.testKind(PopKeys.Kind.MENU)!!
        assertTrue(menuKey.bottom > 0f, "the ⋮ key is in the bar's row")
        // ⋮ opens the bar's menu over the keyboard; its dictations close back to the keyboard
        touch(MotionEvent.ACTION_DOWN, keysMenuKey()); touch(MotionEvent.ACTION_UP, keysMenuKey()); idle(20)
        relayout()
        assertTrue(dock.testMenu)
        menu(DockView.MenuItem.DICTATIONS)
        assertEquals(DockView.MenuPage.DICTATIONS, dock.testMenuPage)
        touch(MotionEvent.ACTION_DOWN, PointF(dock.width - 4f, 2f)); touch(MotionEvent.ACTION_UP, PointF(dock.width - 4f, 2f)); idle(20)
        relayout()
        assertFalse(dock.testMenu)
        assertEquals(DockView.Drawer.KEYS, dock.drawer)
        // pulling the handle down puts the bar back
        pullKeysDown()
        relayout()
        assertEquals(DockView.Drawer.NONE, dock.drawer)
        assertTrue(dock.testHas(DockView.Act.KEYBOARD))
        assertEquals(listOf("keys open", "keys closed"), talk, "the menu over the keyboard leaves it up")
        // the menu on its own still closes to the bar
        tap(DockView.Act.MORE)
        relayout()
        tap(DockView.Act.MORE)
        relayout()
        assertEquals(DockView.Drawer.NONE, dock.drawer)
        // a hold on ABC is Gboard
        val abc = at(DockView.Act.KEYBOARD)
        touch(MotionEvent.ACTION_DOWN, abc); idle(500); touch(MotionEvent.ACTION_UP, abc); idle(20)
        assertEquals(1, toGboard)
        assertEquals(DockView.Drawer.NONE, dock.drawer)
        // and so is a hold on the keyboard's ⋮
        tap(DockView.Act.KEYBOARD)
        relayout()
        val vb2 = keysMenuKey()
        touch(MotionEvent.ACTION_DOWN, vb2); idle(500); touch(MotionEvent.ACTION_UP, vb2); idle(20)
        relayout()
        assertEquals(2, toGboard)
        assertEquals(DockView.Drawer.NONE, dock.drawer)
    }

    @Test fun withTheSettingSwappedATapIsGboardAndAHoldIsOurKeyboard() {
        Prefs.get(activity).edit().putString(Prefs.ABC_TAP, Prefs.ABC_TAP_GBOARD).commit()
        dock.reloadSettings()
        relayout()
        tap(DockView.Act.KEYBOARD)
        assertEquals(1, toGboard)
        val abc = at(DockView.Act.KEYBOARD)
        touch(MotionEvent.ACTION_DOWN, abc); idle(500); touch(MotionEvent.ACTION_UP, abc); idle(20)
        relayout()
        assertEquals(DockView.Drawer.KEYS, dock.drawer)
    }

    @Test fun slidingAlongTheIdleVoicePillMovesTheCursor() {
        val s = at(DockView.Act.MIC)
        touch(MotionEvent.ACTION_DOWN, s)
        touch(MotionEvent.ACTION_MOVE, PointF(s.x + 40 * d, s.y))
        touch(MotionEvent.ACTION_UP, PointF(s.x + 40 * d, s.y))
        assertTrue(keys.isNotEmpty() && keys.all { it == Bar.Key.RIGHT }, "$keys")
    }

    @Test fun cursorBecomesTheTrackpadAndDone() {
        tap(DockView.Act.CURSOR)
        relayout()
        assertEquals(DockView.Drawer.PAD, dock.drawer)
        assertTrue(dock.testHas(DockView.Act.CHIP_PASTE))
        val p = at(DockView.Act.PAD)
        touch(MotionEvent.ACTION_DOWN, p)
        touch(MotionEvent.ACTION_MOVE, PointF(p.x - 30 * d, p.y))
        touch(MotionEvent.ACTION_UP, PointF(p.x - 30 * d, p.y))
        assertEquals("start", stick.first())
        assertEquals(listOf("tap", "end"), stick.takeLast(2))
        dock.canPaste = true
        tap(DockView.Act.CHIP_PASTE)
        assertEquals(listOf(Bar.EditAction.PASTE), edits)
        tap(DockView.Act.CURSOR) // now Done
        assertEquals(DockView.Drawer.NONE, dock.drawer)
    }

    @Test fun trackpadArrowsAreSingleTapsOnly() {
        tap(DockView.Act.CURSOR)
        relayout()
        val r = dock.testItem(DockView.Act.PAD)!!
        val left = PointF(r.left + 30 * d, r.centerY())
        val right = PointF(r.right - 30 * d, r.centerY())
        val up = PointF(r.centerX(), r.top + 20 * d)
        val down = PointF(r.centerX(), r.bottom - 20 * d)
        for ((p, want) in listOf(left to "step -1 0 aim", right to "step 1 0 aim", up to "step 0 -1 aim", down to "step 0 1 aim")) {
            stick.clear()
            touch(MotionEvent.ACTION_DOWN, p); idle(80); touch(MotionEvent.ACTION_UP, p); idle(20)
            assertEquals(listOf(want), stick.filter { it.startsWith("step") }, "a tap is one step")
        }
        // held on an arrow: no step and no repeat, it's the trackpad's hold-to-select
        stick.clear()
        touch(MotionEvent.ACTION_DOWN, left); idle(1500); touch(MotionEvent.ACTION_UP, left); idle(20)
        assertTrue(stick.none { it.startsWith("step") }, "$stick")
        assertTrue("select" in stick, "$stick")
        // a drag that starts on an arrow is a trackpad drag, never an arrow step
        stick.clear()
        touch(MotionEvent.ACTION_DOWN, left)
        touch(MotionEvent.ACTION_MOVE, PointF(left.x + 60 * d, left.y))
        touch(MotionEvent.ACTION_UP, PointF(left.x + 60 * d, left.y)); idle(20)
        val steps = stick.filter { it.startsWith("step") }
        assertTrue(steps.isNotEmpty() && steps.none { it.startsWith("step -1") }, "$stick")
    }

    private fun multi(action: Int, idx: Int, pts: List<PointF>) {
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].x; y = pts[i].y } }
        val a = if (action == MotionEvent.ACTION_MOVE) action else action or (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), a, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        dock.dispatchTouchEvent(e); e.recycle()
    }

    @Test fun aSecondFingerKeepsASelectionGoing() {
        tap(DockView.Act.CURSOR)
        relayout()
        val p = at(DockView.Act.PAD)
        val thumb = PointF(p.x - 120 * d, p.y)
        touch(MotionEvent.ACTION_DOWN, thumb)
        idle(400)
        repeat(3) {
            val start = PointF(p.x, p.y)
            multi(MotionEvent.ACTION_POINTER_DOWN, 1, listOf(thumb, start))
            multi(MotionEvent.ACTION_MOVE, 0, listOf(thumb, PointF(start.x + 60 * d, start.y)))
            multi(MotionEvent.ACTION_POINTER_UP, 1, listOf(thumb, PointF(start.x + 60 * d, start.y)))
        }
        touch(MotionEvent.ACTION_UP, thumb)
        val cols = stick.filter { it.startsWith("step") && it.endsWith("sel") }.sumOf { it.split(" ")[1].toInt() }
        assertTrue(cols >= 18, "three strokes: $stick")
        assertFalse("tap" in stick)
        assertEquals("end", stick.last())
    }


    @Test fun theMenuOpensSettingsAndSwitches() {
        menu(DockView.MenuItem.SETTINGS)
        assertTrue(settings)
        assertFalse(dock.testMenu)
        menu(DockView.MenuItem.SWITCH)
        assertTrue(picker)
    }

    @Test fun theKeyboardsMenuKeyOpensTheSameList() {
        tap(DockView.Act.KEYBOARD)
        relayout()
        val k = keysMenuKey()
        touch(MotionEvent.ACTION_DOWN, k); touch(MotionEvent.ACTION_UP, k); idle(20)
        relayout()
        assertTrue(dock.testMenu)
        assertEquals(DockView.Drawer.KEYS, dock.drawer, "over the keyboard, which stays up")
        val paste = assertNotNull(dock.testMenuAt(DockView.MenuItem.PASTE))
        assertTrue(paste.y < k.y && paste.y >= 0, "above the key, on screen")
    }

    @Test fun everyStateDrawsMidAnimationToo() {
        val bmp = android.graphics.Bitmap.createBitmap(dock.width, 900, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        fun frames() { repeat(4) { dock.draw(c); idle(16) } }
        frames()
        tap(DockView.Act.MIC); dock.showListening(); dock.showLevel(0.5f); dock.showLiveText("hello there"); frames()
        tap(DockView.Act.MIC); dock.showCleaning("hello there"); frames()
        dock.hideVoice(); dock.showMessage("Add an ElevenLabs key"); frames(); dock.hideVoice(); frames()
        tap(DockView.Act.CURSOR); relayout(); frames()
        tap(DockView.Act.CURSOR); frames(); tap(DockView.Act.MORE); relayout(); frames()
        menu(DockView.MenuItem.DICTATIONS); frames()
    }
}
