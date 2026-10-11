package com.spizganed.quickbuds.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.spizganed.quickbuds.R
import com.spizganed.quickbuds.bluetooth.AudioDevice
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.BudsDevice
import com.spizganed.quickbuds.bluetooth.BudsService
import com.spizganed.quickbuds.bluetooth.ConnectedDevices
import com.spizganed.quickbuds.bluetooth.FastPairBattery
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.proximity.BroadcastDistance
import com.spizganed.quickbuds.proximity.BroadcastMatch
import com.spizganed.quickbuds.proximity.BroadcastTracker
import com.spizganed.quickbuds.proximity.DirectionFinder
import com.spizganed.quickbuds.proximity.DistanceBand
import com.spizganed.quickbuds.proximity.DistanceEstimate
import com.spizganed.quickbuds.proximity.FindConfig
import com.spizganed.quickbuds.proximity.FindSound
import com.spizganed.quickbuds.proximity.FindSource
import com.spizganed.quickbuds.proximity.FindThresholds
import com.spizganed.quickbuds.proximity.GattRssiSource
import com.spizganed.quickbuds.proximity.LinkScale
import com.spizganed.quickbuds.proximity.LossNotifier
import com.spizganed.quickbuds.proximity.MotionMeter
import com.spizganed.quickbuds.proximity.SearchCoach
import com.spizganed.quickbuds.proximity.ProximityClassifier
import com.spizganed.quickbuds.proximity.ProximityLevel
import com.spizganed.quickbuds.proximity.RssiProcessor
import com.spizganed.quickbuds.proximity.RssiScale
import com.spizganed.quickbuds.proximity.SignalTrendAnalyzer
import com.spizganed.quickbuds.proximity.Stability
import com.spizganed.quickbuds.proximity.Trend
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.ModelCatalog
import com.spizganed.quickbuds.protocol.OpoProtocol
import com.spizganed.quickbuds.widget.WidgetStateStore
import java.util.Locale

/**
 * Find nearby: how close a connected device is, from its Bluetooth signal. Any brand, any kind.
 *
 * Two sources, each with its own [RssiProcessor] and scale ([FindSource]):
 *  - the device's own Fast Pair broadcast, dBm, matched to it by its battery levels ([BroadcastTracker]): it tells
 *    touching from 1 m, so it leads while it is fresh;
 *  - the connection's RSSI ([GattRssiSource], every [READ_MS]): dBm, or the golden range ([LinkScale]).
 * [ProximityClassifier] turns the filtered signal into a state with hysteresis; [SignalTrendAnalyzer] says whether
 * it improves. Metres on every reading from the device's own stated 1 m level ([DistanceEstimate]), shown as
 * approximate; no setup. No direction: signal strength has none, and neither these earbuds nor this phone measure one.
 *
 * Everything runs on the main thread, redrawn every [READ_MS] whether readings come or not, so a lost signal shows.
 */
class FindNearbyActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val config = FindConfig()
    private lateinit var address: String
    private var isBuds = false
    private var openedAt = 0L

    // ---- Connection RSSI
    private var source: GattRssiSource? = null
    private val linkScale = LinkScale()
    private var linkProc: RssiProcessor? = null
    private var linkFailures = 0
    /** The read in flight: a reading for an older one is dropped, and one never answered counts as missed. */
    private var awaiting = 0
    private var seq = 0
    private var reading = false

    // ---- Fast Pair broadcast
    private val bleProc = RssiProcessor(RssiScale.DBM, config)
    private var tracker = BroadcastTracker(severalAllowed = true)
    /** The 1 m level the broadcast states (Tx Power): the reference for the states and the distance. */
    private var statedReference = BroadcastDistance.DEFAULT_AT_ONE_METRE_DBM
    private var scanning = false
    private var scanStartedAt = 0L
    private var bleHeard = 0

    // ---- State
    private val classifier = ProximityClassifier(config)
    private var signalEverSeen = false
    private var bluetoothOn = true
    private var active: FindSource? = null
    private var shownLevel: ProximityLevel? = null
    private val sound = FindSound()
    /** The guide's line: wait, hold steady, slow down, closer, weaker... ([SearchCoach]). */
    private val coach = SearchCoach()
    /** Whether the phone is swung around too fast ([MotionMeter]), from the linear acceleration while on screen. */
    private val motion = MotionMeter()

    // ---- Direction (Arrow tab) and the radar
    /** The tab shown: Arrow or Radar, remembered. */
    private var mode = FinderView.Mode.RADAR
    private val finder = DirectionFinder()
    /** Where the device is, from the user's turn; null until a turn gives a clear answer. Kept until "again". */
    private var bearing: Double? = null
    /** When the bearing was found: it expires after [BEARING_MS], the user has walked on. */
    private var bearingAt = 0L
    /** A broadcast of this device was matched since the screen opened: the turn is read from it alone. */
    private var broadcastSeen = false
    /** The last battery levels the loss card saw (left, right, case; or one level): to match the broadcast after a drop. */
    private var cardLevels: IntArray? = null
    private var heading: Double? = null
    private var compass = false
    /** Which reading feeds the direction finder: one turn never mixes dBm with the golden range. */
    private var directionFromBle: Boolean? = null
    private var band: DistanceBand? = null
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var manager: BudsConnectionManager? = null
    private var pulsedAt = 0L
    /** When the very-close stage began: its pulses stop after [HERE_PULSE_FOR_MS], the phone may lie there a while. */
    private var hereSince = 0L
    /** The last source with readings: the graph and the summary keep showing it after the signal is lost. */
    private var lastSource: FindSource? = null
    /** The loss card's tone flag as last seen: a change made there is followed here. */
    private var ringingSeen = false
    private var resumed = false

    // ---- Views
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var nameText: TextView
    private lateinit var connText: TextView
    private lateinit var dial: FinderView
    /** The big line: the distance when there is one ("About 1.5 m", "Here"), else the state. */
    private lateinit var headline: TextView
    /** The state under a distance ("Nearby"); empty when the headline is the state itself. */
    private lateinit var stateLine: TextView
    private lateinit var subline: TextView
    private lateinit var distanceLine: TextView
    private lateinit var guidance: TextView
    private lateinit var summary: TextView
    private lateinit var graph: SignalGraphView
    private lateinit var graphTitle: TextView
    private lateinit var permRow: LinearLayout
    private lateinit var scanBtn: TextView
    private lateinit var playBtn: TextView
    private lateinit var note: TextView
    private lateinit var direction: TextView
    private lateinit var tabs: AncSegmentedView
    private lateinit var arrowStatus: TextView
    private lateinit var againBtn: TextView
    private var diagnostics: TextView? = null
    private lateinit var permText: TextView
    private lateinit var rawBox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeRes.select(this)
        super.onCreate(savedInstanceState)
        address = intent.getStringExtra(EXTRA_ADDRESS) ?: return finish()
        isBuds = BudsDevice.find(this)?.address == address
        cardLevels = intent.getIntArrayExtra(EXTRA_LEVELS)
        tracker = BroadcastTracker(severalAllowed = isBuds)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildViews()
        openedAt = SystemClock.elapsedRealtime()
    }

    // ==================== Lifecycle ====================

    override fun onResume() {
        super.onResume()
        resumed = true
        LossNotifier.toneHeldByFindScreen = true
        val now = SystemClock.elapsedRealtime()
        // Back after the signals went stale: the old state proves nothing, search again.
        if (bleProc.stale(now) && linkProc?.stale(now) != false) {
            classifier.reset(); signalEverSeen = false; openedAt = now
            clearDirection(); coach.reset()
        }
        registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        bluetoothOn = adapter()?.isEnabled == true
        bindService(Intent(this, BudsService::class.java), serviceConnection, 0)
        if (bluetoothOn) { startReading(); startScan() }
        motion.reset()
        getSystemService(android.hardware.SensorManager::class.java)?.let { sm ->
            sm.getDefaultSensor(android.hardware.Sensor.TYPE_LINEAR_ACCELERATION)?.let {
                sm.registerListener(motionListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI)
            }
            val rv = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
                ?: sm.getDefaultSensor(android.hardware.Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
            compass = rv != null
            rv?.let { sm.registerListener(compassListener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        }
        handler.post(tick)
    }

    override fun onPause() {
        resumed = false
        getSystemService(android.hardware.SensorManager::class.java)?.let { it.unregisterListener(motionListener); it.unregisterListener(compassListener) }
        handler.removeCallbacksAndMessages(null)
        stopReading()
        stopScan()
        runCatching { unregisterReceiver(adapterReceiver) }
        // A tone this screen may have started stops with it: playing, or asked for and not (yet) confirmed.
        if (sound.state == FindSound.State.PLAYING || sound.state == FindSound.State.PENDING || sound.state == FindSound.State.FAILED) {
            startService(Intent(this, BudsService::class.java).setAction(LossNotifier.ACTION_FIND).putExtra(LossNotifier.EXTRA_ON, false))
            sound.syncPlaying(false)
        }
        LossNotifier.toneHeldByFindScreen = false
        manager?.removeListener(managerListener)
        manager = null
        runCatching { unbindService(serviceConnection) }
        super.onPause()
    }

    /** One beat: a connection read, then the screen from whatever the sources hold now. */
    private val tick = object : Runnable {
        override fun run() {
            readLink()
            val before = sound.state
            sound.tick(SystemClock.elapsedRealtime())
            // No answer in time: say so; the button alone going back to "Play" says nothing.
            if (before == FindSound.State.PENDING && sound.state == FindSound.State.FAILED) toast(R.string.find_sound_failed)
            // Switched on the loss card: follow it.
            if (LossNotifier.ringing != ringingSeen) { ringingSeen = LossNotifier.ringing; sound.syncPlaying(ringingSeen) }
            render()
            handler.postDelayed(this, READ_MS)
        }
    }

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    bluetoothOn = false
                    stopReading(); stopScan()
                }
                BluetoothAdapter.STATE_ON -> {
                    bluetoothOn = true
                    startReading(); startScan()
                }
            }
        }
    }

    private fun adapter(): BluetoothAdapter? = getSystemService(BluetoothManager::class.java)?.adapter

    // ==================== Connection RSSI ====================

    private fun connectAllowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun startReading() {
        if (reading) return
        val device = if (connectAllowed()) runCatching { adapter()?.getRemoteDevice(address) }.getOrNull() else null
        if (device == null) { linkFailures = LOST_AFTER; return }
        source = GattRssiSource(this, device).also { it.open() }
        reading = true
        linkFailures = 0
    }

    private fun stopReading() {
        reading = false
        awaiting = 0
        source?.close()
        source = null
    }

    private fun readLink() {
        val s = source ?: return
        if (!reading) return
        if (awaiting != 0) {
            // The last read never came back within a beat: it counts as missed.
            s.cancelPending()
            awaiting = 0
            onLink(null)
        }
        val mine = ++seq
        awaiting = mine
        s.read { rssi -> handler.post { if (awaiting == mine) { awaiting = 0; onLink(rssi) } } }
    }

    private fun onLink(rssi: Int?) {
        val now = SystemClock.elapsedRealtime()
        if (rssi == null) { linkFailures++; return }
        if (linkScale.onReading(rssi)) {
            linkProc = linkScale.scale?.let { RssiProcessor(it, config) }
            PacketLogger.log("FIND: connection scale ${linkScale.scale}")
        }
        val p = linkProc ?: return
        // Back after a gap: the old window is about another place (it could jump straight to "very close").
        if (p.stale(now) && p.count > 0) p.reset()
        if (!p.add(rssi, now)) { linkFailures++; return }
        linkFailures = 0
        signalEverSeen = true
        logWindow(FindSource.LINK, now)
        // The connection feeds the turn only when no broadcast of this device was ever heard: the two never take
        // turns, so a broadcast pause does not wipe the circle.
        if (!broadcastSeen) addDirection(rssi.toDouble(), fromBle = false, now = now)
    }

    // ==================== Fast Pair broadcast ====================

    /** Android 12+: Nearby devices. Before: scan results need location (the manifest asks it up to Android 11). */
    private fun scanPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
        else Manifest.permission.ACCESS_FINE_LOCATION

    private fun scanAllowed() = checkSelfPermission(scanPermission()) == PackageManager.PERMISSION_GRANTED

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getServiceData(FAST_PAIR) ?: return
            val levels = FastPairBattery.parse(data) ?: return
            val tx = result.scanRecord?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }
            val from = result.device.address
            val rssi = result.rssi
            handler.post { onAdvert(from, rssi, tx, levels) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                PacketLogger.log("FIND: scan failed ($errorCode)")
                scanning = false
                // Android allows a few scan starts per 30 s: try once more after that, only while on screen.
                if (resumed) handler.postDelayed({ if (resumed && bluetoothOn) startScan() }, SCAN_RETRY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning || !scanAllowed()) return
        val scanner = adapter()?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceData(FAST_PAIR, ByteArray(0)).build()
        // Low latency only while this screen is open: it is the active search, and it stops with the screen.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanning = runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure { PacketLogger.error("find scan", it) }.isSuccess
        scanStartedAt = SystemClock.elapsedRealtime()
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(scanCallback) }
        tracker.clear()
    }

    private fun onAdvert(from: String, rssi: Int, tx: Int?, levels: FastPairBattery.Levels) {
        if (!scanning) return
        val now = SystemClock.elapsedRealtime()
        bleHeard++
        val card = cardLevels
        val matches = if (isBuds) {
            val st = WidgetStateStore.read(this)
            // After a drop the buds' levels are cleared: the loss card kept the last ones.
            fun live(v: Int, i: Int) = if (v > 0) v else card?.getOrNull(i) ?: -1
            BroadcastMatch.buds(levels, live(st.leftBattery, 0), live(st.rightBattery, 1), live(st.caseBattery, 2))
        } else BroadcastMatch.single(levels,
            ConnectedDevices.all().firstOrNull { it.address == address }?.level?.takeIf { it > 0 } ?: card?.getOrNull(0) ?: -1)
        val r = tracker.onAdvert(from, rssi, tx, matches, now) ?: return
        // Back after a gap: the old smoothing is about another place.
        if (bleProc.stale(now)) {
            if (bleProc.count > 0) bleProc.reset()
            PacketLogger.log("FIND: broadcast of this device found")
        }
        if (!bleProc.add(r.rssi, now)) return
        statedReference = r.atOneMetreDbm
        signalEverSeen = true
        logWindow(FindSource.BROADCAST, now)
        if (bleProc.ready(now)) {
            broadcastSeen = true
            addDirection(r.rssi.toDouble(), fromBle = true, now = now)
        }
    }

    // ==================== Choosing and judging ====================

    private fun processor(s: FindSource?): RssiProcessor? = when (s) {
        FindSource.BROADCAST -> bleProc
        FindSource.LINK -> linkProc
        null -> null
    }

    /**
     * The broadcast once it is ready (dBm, tells touching from 1 m), else the connection while it is ready or fresh,
     * else a fresh broadcast still collecting. Was: any fresh broadcast won, so each broadcast pause dropped a judged
     * state back to "Calibrating".
     */
    private fun pickSource(now: Long): FindSource? = when {
        bleProc.ready(now) -> FindSource.BROADCAST
        linkProc?.ready(now) == true -> FindSource.LINK
        linkProc?.stale(now) == false -> FindSource.LINK
        !bleProc.stale(now) -> FindSource.BROADCAST
        else -> null
    }

    private fun scaleOf(s: FindSource?) = if (s == FindSource.BROADCAST) RssiScale.DBM else linkProc?.scale

    private fun thresholds(s: FindSource?): FindThresholds? = when (s) {
        FindSource.BROADCAST -> FindThresholds.dbm(statedReference)
        FindSource.LINK -> when (linkProc?.scale) {
            RssiScale.GOLDEN_RANGE -> FindThresholds.GOLDEN
            RssiScale.DBM -> FindThresholds.dbm(LINK_DEFAULT_REFERENCE)
            null -> null
        }
        null -> null
    }

    // ==================== Screen ====================

    private fun render() {
        val now = SystemClock.elapsedRealtime()
        val src = pickSource(now)
        if (src != active) {
            PacketLogger.log("FIND: source ${src ?: "none"}")
            active = src
        }
        if (src != null) lastSource = src
        val p = processor(src)
        val filtered = p?.filtered?.takeIf { !p.stale(now) }
        val linkUp = reading && linkFailures < LOST_AFTER
        val level = classifier.update(filtered, p?.ready(now) == true, p?.stability(), thresholds(src), signalEverSeen,
            now - openedAt, linkUp || !bleProc.stale(now), bluetoothOn)
        if (level != shownLevel) {
            PacketLogger.log("FIND: state $level (${classifier.reason})")
            onLevelChange(shownLevel, level)
            shownLevel = level
        }
        val stability = p?.stability()
        val trend = if (p != null) SignalTrendAnalyzer.trend(p.samples(now), stability, now, config) else Trend.UNKNOWN

        // A direction holds while the user follows it: lost with the signal, when it keeps weakening, or after a while.
        if (bearing != null && (now - bearingAt > BEARING_MS || trend == Trend.WEAKENING ||
                level == ProximityLevel.NO_SIGNAL || level == ProximityLevel.DEVICE_DISCONNECTED || level == ProximityLevel.BLUETOOTH_OFF)) {
            clearDirection()
        }
        val estimate = estimateMetres(level, src, filtered)
        val golden = scaleOf(src) == RssiScale.GOLDEN_RANGE
        band = when {
            level == ProximityLevel.VERY_CLOSE -> DistanceBand.REACHED
            // "Reached" only with the proven very-close state: a strong reading alone says near.
            estimate != null -> DistanceBand.of(estimate, band?.takeIf { it != DistanceBand.REACHED })
                .let { if (it == DistanceBand.REACHED) DistanceBand.NEAR else it }
            // No number: beyond 30 m on dBm; within about 1 m on the golden range (it reads 0 there), so near.
            !golden && (level == ProximityLevel.FAR || level == ProximityLevel.NEARBY) -> DistanceBand.BEYOND
            golden && level == ProximityLevel.NEARBY -> DistanceBand.NEAR
            else -> null
        }
        dial.level = level
        dial.metres = estimate
        dial.band = band
        dial.bearing = bearing
        dial.heading = heading
        dial.covered = finder.covered(now)
        renderTab(level, now)
        if (level == ProximityLevel.VERY_CLOSE && now - hereSince < HERE_PULSE_FOR_MS && now - pulsedAt >= HERE_PULSE_MS) {
            pulsedAt = now; Haptics.tick(this)
        }

        val (titleRes, subRes) = texts(level)
        // The distance is the big line, as before; the state goes under it. With no distance, the state is the big line.
        val d = distance(level, src, filtered)
        if (d != null) {
            headline.text = d.big
            // Under the distance, its band: too weak, getting closer, better, near, reached.
            stateLine.setText(band?.let { bandText(it) } ?: titleRes)
        } else {
            headline.setText(titleRes)
            stateLine.text = band?.takeIf { level != ProximityLevel.VERY_CLOSE }?.let { getString(bandText(it)) }
                ?: if (level == ProximityLevel.VERY_CLOSE) getString(R.string.find_band_reached) else ""
        }
        // The coach line guides; the subline only carries what the coach does not: the way out of a lost connection.
        subline.text = when (level) {
            ProximityLevel.DEVICE_DISCONNECTED, ProximityLevel.BLUETOOTH_OFF, ProximityLevel.NO_SIGNAL -> getString(subRes)
            ProximityLevel.VERY_CLOSE -> if (canPlay()) "" else getString(R.string.find_state_close_look)
            else -> ""
        }
        distanceLine.text = d?.note.orEmpty()
        val near = band?.let { it == DistanceBand.NEAR || it == DistanceBand.REACHED }
        val turning = mode == FinderView.Mode.ARROW && compass && bearing == null &&
            (level == ProximityLevel.FAR || level == ProximityLevel.NEARBY || level == ProximityLevel.SIGNAL_UNSTABLE)
        guidance.text = coachText(coach.update(level, trend, motion.fast, canPlay(), signalEverSeen, now, near, turning))
        // The graph and the summary keep the last source: after a loss they show where the signal was, and since when.
        val shown = processor(src ?: lastSource)
        summary.text = summaryText(src ?: lastSource, shown, shown?.stability(), trend, now)
        graph.nowMs = now
        graph.unit = if (scaleOf(src ?: lastSource) == RssiScale.GOLDEN_RANGE) "" else "dBm"
        graph.samples = shown?.samples(now).orEmpty()
        connText.text = getString(if (src == FindSource.BROADCAST) R.string.find_sum_source_broadcast else R.string.find_sum_source_link)
        connText.visibility = if (src == null) View.INVISIBLE else View.VISIBLE
        permRow.visibility = if (!scanAllowed()) View.VISIBLE else View.GONE
        renderButtons(level, src)
        diagnostics?.takeIf { it.visibility == View.VISIBLE }?.let { renderDiagnostics(it, src, p, level, now) }
    }

    private fun bandText(b: DistanceBand) = when (b) {
        DistanceBand.REACHED -> R.string.find_band_reached
        DistanceBand.NEAR -> R.string.find_band_near
        DistanceBand.BETTER -> R.string.find_band_better
        DistanceBand.CLOSER -> R.string.find_band_closer
        DistanceBand.WEAK -> R.string.find_band_weak
        DistanceBand.BEYOND -> R.string.find_band_beyond
    }

    /**
     * Metres for the radar and the bands. dBm: from the stated 1 m level. Golden range (connection only): its level
     * as dBm under the range's lower edge, so only the far bands; 0 and above (within about 1 m) gives no number.
     */
    private fun estimateMetres(level: ProximityLevel, src: FindSource?, filtered: Double?): Double? {
        if (src == null || filtered == null) return null
        if (level != ProximityLevel.FAR && level != ProximityLevel.NEARBY && level != ProximityLevel.SIGNAL_UNSTABLE) return null
        return if (scaleOf(src) == RssiScale.GOLDEN_RANGE) {
            if (filtered >= -1) null else DistanceEstimate.metres(DistanceEstimate.GOLDEN_LOWER_DBM, DistanceEstimate.goldenAsDbm(filtered))
        } else DistanceEstimate.metres(thresholds(src)?.reference ?: return null, filtered)
    }

    /** The text and the button under the dial for the tab shown. Right here, the pulse says it all: no turn text. */
    private fun renderTab(level: ProximityLevel, now: Long) {
        dial.mode = mode
        tabs.selected = mode.ordinal
        if (level == ProximityLevel.VERY_CLOSE) {
            arrowStatus.text = ""
            againBtn.visibility = if (mode == FinderView.Mode.ARROW) View.INVISIBLE else View.GONE
            direction.setText(if (mode == FinderView.Mode.ARROW) R.string.find_arrow_note else R.string.find_radar_note)
            return
        }
        if (mode == FinderView.Mode.ARROW) {
            val r = if (bearing != null) null else finder.result(now)
            arrowStatus.text = when {
                !compass -> getString(R.string.find_arrow_no_compass)
                bearing != null -> getString(R.string.find_arrow_found)
                r == DirectionFinder.Result.Unclear -> getString(R.string.find_arrow_unclear)
                r is DirectionFinder.Result.Turning && r.coverage > 0 -> getString(R.string.find_arrow_keep, (r.coverage * 100).toInt())
                else -> getString(R.string.find_arrow_turn)
            }
            direction.setText(R.string.find_arrow_note)
            // Invisible, not gone, on this tab: the layout does not jump when it appears.
            againBtn.visibility = if (compass && (bearing != null || r == DirectionFinder.Result.Unclear)) View.VISIBLE else View.INVISIBLE
        } else {
            arrowStatus.text = if (bearing == null && dial.metres != null) getString(R.string.find_radar_no_direction) else ""
            direction.setText(R.string.find_radar_note)
            againBtn.visibility = View.GONE
        }
    }

    private fun texts(level: ProximityLevel): Pair<Int, Int> = when (level) {
        ProximityLevel.SEARCHING -> R.string.find_state_searching_t to R.string.find_state_searching_s
        ProximityLevel.CALIBRATING -> R.string.find_state_calibrating_t to R.string.find_state_calibrating_s
        ProximityLevel.FAR -> R.string.find_state_far_t to R.string.find_state_far_s
        ProximityLevel.NEARBY -> R.string.find_state_nearby_t to R.string.find_state_nearby_s
        ProximityLevel.VERY_CLOSE -> R.string.find_nav_here to R.string.find_state_close_s
        ProximityLevel.SIGNAL_UNSTABLE -> R.string.find_state_unstable_t to R.string.find_state_unstable_s
        ProximityLevel.NO_SIGNAL -> R.string.find_state_nosignal_t to R.string.find_state_nosignal_s
        ProximityLevel.DEVICE_DISCONNECTED -> R.string.find_state_disconnected_t to R.string.find_state_disconnected_s
        ProximityLevel.BLUETOOTH_OFF -> R.string.find_state_bt_off_t to R.string.find_state_bt_off_s
    }

    /** The big distance line, and the small note under it, if any. */
    private class Distance(val big: String, val note: String?)

    /**
     * Metres for the weak and nearby states, on every reading, from the 1 m level the device states itself: shown as
     * approximate, 0.5 to 5 m, else "more than 5 m". Only on a dBm source: the golden range reads 0 for everything
     * within about 1 m, so no number comes from it. Null: no number; the state is the big line.
     */
    private fun distance(level: ProximityLevel, src: FindSource?, filtered: Double?): Distance? {
        if ((level != ProximityLevel.FAR && level != ProximityLevel.NEARBY) || src == null || filtered == null) return null
        if (scaleOf(src) == RssiScale.GOLDEN_RANGE) return null
        val reference = thresholds(src)?.reference ?: return null
        val m = DistanceEstimate.metres(reference, filtered)
            ?: return Distance(getString(R.string.find_dist_far_estimate, DistanceEstimate.MAX_M.toInt()), null)
        return Distance(getString(R.string.find_nav_meters, metres(m)), getString(R.string.find_dist_estimate_note))
    }

    private fun metres(m: Double): String {
        val r = DistanceEstimate.rounded(m)
        return if (r % 1.0 == 0.0) r.toInt().toString() else String.format(Locale.getDefault(), "%.1f", r)
    }

    /** The earbuds can ring from here: supported buds with a find tone. */
    private fun canPlay() = isBuds && Capabilities.supports(this, OpoProtocol.CMD_FIND_BUDS)

    private fun coachText(tip: SearchCoach.Tip): String = when (tip) {
        SearchCoach.Tip.SEARCHING -> getString(R.string.find_coach_searching)
        SearchCoach.Tip.WAIT -> getString(R.string.find_coach_wait)
        SearchCoach.Tip.STEADY -> getString(R.string.find_coach_steady)
        SearchCoach.Tip.SLOW -> getString(R.string.find_coach_slow)
        SearchCoach.Tip.CLOSER -> getString(R.string.find_coach_closer)
        SearchCoach.Tip.WEAKER -> getString(R.string.find_coach_weaker)
        SearchCoach.Tip.TRY_OTHER -> getString(R.string.find_coach_try_other)
        SearchCoach.Tip.NEARBY -> getString(R.string.find_coach_nearby)
        SearchCoach.Tip.HERE_PLAY -> getString(R.string.find_coach_here_play)
        SearchCoach.Tip.HERE -> getString(R.string.find_coach_here)
        SearchCoach.Tip.LOST -> getString(R.string.find_coach_lost)
        SearchCoach.Tip.FAR -> getString(R.string.find_coach_far)
        SearchCoach.Tip.NONE -> ""
    }

    /**
     * The compass: where the phone points. Upright in front of the user, its back points ahead, so that axis is
     * measured, not the top edge. Only while this screen is open.
     */
    private val compassListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            android.hardware.SensorManager.getRotationMatrixFromVector(rotation, e.values)
            val tilt = Math.toDegrees(Math.acos(rotation[8].toDouble().coerceIn(-1.0, 1.0)))
            if (tilt in 45.0..135.0) {
                android.hardware.SensorManager.remapCoordinateSystem(rotation, android.hardware.SensorManager.AXIS_X,
                    android.hardware.SensorManager.AXIS_Z, remapped)
                android.hardware.SensorManager.getOrientation(remapped, orientation)
            } else android.hardware.SensorManager.getOrientation(rotation, orientation)
            val h = DirectionFinder.normalize(Math.toDegrees(orientation[0].toDouble()))
            val before = heading
            heading = h
            // The dial follows the phone's turn smoothly between beats.
            if (before == null || Math.abs(DirectionFinder.turn(before, h)) >= 1.0) {
                dial.heading = h
                if (mode == FinderView.Mode.ARROW) dial.covered = finder.covered(SystemClock.elapsedRealtime())
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    private fun clearDirection() {
        bearing = null; finder.reset(); directionFromBle = null
    }

    /** A raw reading for the direction finder; a change of source starts the turn over. Raw: smoothing lags a turn. */
    private fun addDirection(level: Double, fromBle: Boolean, now: Long) {
        val h = heading ?: return
        if (bearing != null) return
        if (directionFromBle != fromBle) { finder.reset(); directionFromBle = fromBle }
        finder.add(h, level, now)
        val r = finder.result(now)
        if (r is DirectionFinder.Result.Found) {
            bearing = r.bearing
            bearingAt = now
            PacketLogger.log("FIND: direction found from the turn")
            Haptics.tick(this)
        }
    }

    /** The phone's motion, gravity removed: only while this screen is open. */
    private val motionListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val (x, y, z) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
            motion.add(Math.sqrt(x * x + y * y + z * z))
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }

    private fun summaryText(src: FindSource?, p: RssiProcessor?, stability: Stability?, trend: Trend, now: Long): String {
        val f = p?.filtered ?: return ""
        val lines = ArrayList<String>()
        lines += if (scaleOf(src) == RssiScale.GOLDEN_RANGE) getString(R.string.find_sum_link, Math.round(f).toInt())
            else getString(R.string.find_sum_signal, Math.round(f).toInt())
        stability?.let {
            lines += getString(when (it) {
                Stability.STABLE -> R.string.find_sum_stable
                Stability.FLUCTUATING -> R.string.find_sum_fluctuating
                Stability.UNSTABLE -> R.string.find_sum_unstable
            })
        }
        lines += getString(when (trend) {
            Trend.IMPROVING -> R.string.find_sum_improving
            Trend.WEAKENING -> R.string.find_sum_weakening
            Trend.STEADY -> R.string.find_sum_steady
            Trend.FLUCTUATING -> R.string.find_sum_fluctuating
            Trend.UNKNOWN -> R.string.find_sum_trend_unknown
        })
        val ago = ((now - p.lastAt) / 1000).toInt()
        lines += if (ago < 1) getString(R.string.find_sum_last_now) else getString(R.string.find_sum_last_s, ago)
        return lines.joinToString("\n")
    }

    /** The very-close stage turns the screen the accent colour; leaving it turns it back. Haptics mark the way in. */
    private fun onLevelChange(from: ProximityLevel?, to: ProximityLevel) {
        val p = ThemeRes.palette(this)
        val wasHere = from == ProximityLevel.VERY_CLOSE
        val here = to == ProximityLevel.VERY_CLOSE
        if (wasHere != here) {
            android.animation.ValueAnimator.ofArgb(if (here) p.background else p.accent, if (here) p.accent else p.background).apply {
                duration = 350
                addUpdateListener { root.setBackgroundColor(it.animatedValue as Int) }
                start()
            }
            // On the accent fill the readable colour is onAccent, in every style (onFill is for a dotted pill on a card).
            val ink = if (here) p.onAccent else ThemeRes.color(this, R.attr.appColorTextPrimary)
            val soft = if (here) p.onAccent else ThemeRes.color(this, R.attr.appColorTextSecondary)
            listOf(title, headline, stateLine, subline).forEach { it.setTextColor(ink) }
            guidance.setTextColor(if (here) ink else p.accent)
            listOf(nameText, connText, distanceLine, summary, note, direction, graphTitle, rawBox, permText, arrowStatus).forEach { it.setTextColor(soft) }
            diagnostics?.setTextColor(soft)
            graph.lineColor = if (here) p.onAccent else p.accent
            graph.inkColor = soft
            playBtn.setTextColor(if (here) p.accent else ThemeRes.onFill(this))
            playBtn.background = ThemeRes.ripple(this, if (here) ThemeRes.card(this, 24f) else ThemeRes.pill(this, p.accent, 24f))
            if (here) {
                hereSince = SystemClock.elapsedRealtime()
                pulsedAt = hereSince
                Haptics.tick(this); handler.postDelayed({ Haptics.tick(this) }, 140)
            }
        } else if (to == ProximityLevel.NEARBY && from == ProximityLevel.FAR) Haptics.tick(this)
    }

    // ==================== Buttons ====================

    private fun renderButtons(level: ProximityLevel, src: FindSource?) {
        val supported = canPlay()
        sound.onCapability(supported, manager?.isConnected() == true)
        playBtn.visibility = if (supported) View.VISIBLE else View.GONE
        // Disconnected stays tappable: the tap says why nothing rings.
        playBtn.isEnabled = sound.state != FindSound.State.PENDING
        playBtn.alpha = if (sound.state == FindSound.State.PENDING || sound.state == FindSound.State.DISCONNECTED) 0.45f else 1f
        playBtn.setText(when (sound.state) {
            FindSound.State.PENDING -> R.string.find_sound_pending
            FindSound.State.PLAYING -> R.string.find_stop
            else -> R.string.find_play
        })
        scanBtn.alpha = if (level == ProximityLevel.BLUETOOTH_OFF) 0.45f else 1f
    }

    private fun scanAgain() {
        bleProc.reset(); tracker.clear(); linkScale.reset(); linkProc = null
        classifier.reset(); coach.reset(); clearDirection(); broadcastSeen = false; band = null; signalEverSeen = false; openedAt = SystemClock.elapsedRealtime(); linkFailures = 0
        // Android allows a few scan starts per 30 s: only restart the scan when the last start is old enough.
        if (!scanning || SystemClock.elapsedRealtime() - scanStartedAt > SCAN_RESTART_MS) { stopScan(); startScan() }
        if (!reading && bluetoothOn) startReading()
        PacketLogger.log("FIND: scan again")
        render()
    }

    /** Play sound: the in-ear warning first, then the command; the button says "Sending" until the earbuds answer. */
    private fun onPlay() {
        if (sound.state == FindSound.State.DISCONNECTED) return toast(R.string.find_sound_disconnected)
        if (sound.playing) return sendTone(false)
        FindBudsSheet.confirmIfWorn(this) { sendTone(true) }
    }

    private fun sendTone(on: Boolean) {
        if (!sound.request(on, SystemClock.elapsedRealtime())) {
            if (sound.state == FindSound.State.DISCONNECTED) toast(R.string.find_sound_disconnected)
            return
        }
        // Through the loss warning's action: its card's Play / Stop follows.
        startService(Intent(this, BudsService::class.java).setAction(LossNotifier.ACTION_FIND).putExtra(LossNotifier.EXTRA_ON, on))
        render()
    }

    private val managerListener = object : BudsConnectionManager.Listener {
        override fun onConnected(connected: Boolean) { render() }
        override fun onBattery(left: Int?, case: Int?, right: Int?, chargingLeft: Boolean, chargingCase: Boolean, chargingRight: Boolean) {}
        override fun onFindToneAck(accepted: Boolean) {
            val before = sound.state
            sound.onAck(accepted)
            if (before == FindSound.State.PENDING && sound.state == FindSound.State.FAILED) toast(R.string.find_sound_failed)
            // Success only now, with the earbuds' own answer.
            if (before == FindSound.State.PENDING && sound.state == FindSound.State.PLAYING) toast(R.string.find_sound_playing)
            render()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            manager = (service as? BudsService.LocalBinder)?.getService()?.manager
            manager?.addListener(managerListener)
            // The capability first: a render before the binding had set "disconnected", which syncPlaying skips.
            sound.onCapability(canPlay(), manager?.isConnected() == true)
            ringingSeen = LossNotifier.ringing
            sound.syncPlaying(ringingSeen)
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) { manager = null }
    }

    private fun toast(res: Int) = android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()

    // ==================== Diagnostics (developer tools on) ====================

    /** English only: a developer view, shown by a long press on the title. No full address. */
    @SuppressLint("SetTextI18n")
    private fun renderDiagnostics(v: TextView, src: FindSource?, p: RssiProcessor?, level: ProximityLevel, now: Long) {
        fun f(d: Double?) = d?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
        v.text = listOf(
            "device …${address.takeLast(5)}  buds=$isBuds  source=${src ?: "-"}  scale=${scaleOf(src) ?: "-"}",
            "raw=${p?.lastRaw ?: "-"}  median=${f(p?.median)}  filtered=${f(p?.filtered)}  n=${p?.count ?: 0}",
            "last=${p?.let { "${now - it.lastAt} ms" } ?: "-"}  sd=${f(p?.spread())}  stability=${p?.stability() ?: "-"}",
            "state=$level  why=${classifier.reason}",
            "reference=${f(thresholds(src)?.reference)}  stated=${f(statedReference)}  estimate=${filteredEstimate(src, p)}",
            "link: reading=$reading failures=$linkFailures scale=${linkScale.scale ?: "-"}",
            "scan: on=$scanning heard=$bleHeard tracked=${tracker.chosen(now)?.takeLast(5) ?: "-"}",
            "sound=${sound.state}  bluetooth=${if (bluetoothOn) "on" else "off"}  direction=none (no ranging hardware)"
        ).joinToString("\n")
    }

    private fun filteredEstimate(src: FindSource?, p: RssiProcessor?): String {
        val f = p?.filtered ?: return "-"
        if (scaleOf(src) != RssiScale.DBM) return "-"
        val r = thresholds(src)?.reference ?: return "-"
        return DistanceEstimate.metres(r, f)?.let { String.format(Locale.US, "%.2f m", it) } ?: "> ${DistanceEstimate.MAX_M} m"
    }

    // ==================== Log ====================

    private val windowFrom = HashMap<FindSource, Long>()
    private val windowReads = HashMap<FindSource, ArrayList<Int>>()

    /** One line per 10 s per source: the raw range, for tuning. No address. */
    private fun logWindow(s: FindSource, now: Long) {
        val list = windowReads.getOrPut(s) { ArrayList() }
        if (list.isEmpty()) windowFrom[s] = now
        processor(s)?.lastRaw?.let { list += it }
        if (now - (windowFrom[s] ?: now) < 10_000) return
        val sorted = list.sorted()
        PacketLogger.log("FIND: 10 s ${s.key} ${scaleOf(s)}: min ${sorted.first()} median ${sorted[sorted.size / 2]} max ${sorted.last()} " +
            "(${sorted.size})" + (if (s == FindSource.BROADCAST) ", 1 m stated ${statedReference.toInt()}, $bleHeard heard" else ""))
        list.clear()
        if (s == FindSource.BROADCAST) bleHeard = 0
    }

    // ==================== Layout ====================

    private fun buildViews() {
        val p = ThemeRes.palette(this)
        fun dp(v: Float) = ThemeRes.dp(this, v)
        fun text(res: Int, size: Float, colorAttr: Int) = TextView(this).apply {
            if (res != 0) setText(res)
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(ThemeRes.color(this@FindNearbyActivity, colorAttr))
        }
        fun lp(top: Float = 0f) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
        fun button(label: Int, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
            setText(label)
            textSize = 15f
            typeface = ThemeRes.medium(context)
            gravity = Gravity.CENTER
            setTextColor(if (primary) ThemeRes.onFill(this@FindNearbyActivity) else p.accent)
            background = ThemeRes.ripple(this@FindNearbyActivity,
                if (primary) ThemeRes.pill(this@FindNearbyActivity, p.accent, 24f) else ThemeRes.card(this@FindNearbyActivity, 24f))
            minimumHeight = dp(48f)
            setPadding(dp(12f), 0, dp(12f), 0)
            setOnClickListener { Haptics.commit(this); onClick() }
        }
        fun row(vararg views: TextView) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { if (i > 0) marginStart = dp(10f) })
            }
        }

        root = SettingRowFactory.screen(this)
        root.setBackgroundColor(p.background)
        title = SettingRowFactory.title(this, R.string.find_nav_title)
        // Developer diagnostics: a long press on the title, remembered. English only, off by default.
        title.setOnLongClickListener {
            val on = !prefs().getBoolean(KEY_DIAGNOSTICS, false)
            prefs().edit().putBoolean(KEY_DIAGNOSTICS, on).apply()
            diagnostics?.visibility = if (on) View.VISIBLE else View.GONE
            true
        }
        root.addView(title)
        nameText = text(0, 15f, R.attr.appColorTextSecondary).apply { gravity = Gravity.START; text = resolveName(); setPadding(dp(4f), 0, 0, 0) }
        root.addView(nameText)
        connText = text(0, 13f, R.attr.appColorTextSecondary).apply { gravity = Gravity.START; setPadding(dp(4f), dp(2f), 0, dp(6f)) }
        root.addView(connText)

        permRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            permText = text(R.string.find_perm_scan, 13f, R.attr.appColorTextPrimary).apply { gravity = Gravity.START }
            addView(permText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(button(R.string.find_perm_allow, false) { askScanPermission() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40f)))
            visibility = View.GONE
        }
        root.addView(permRow, lp(4f))

        mode = runCatching { FinderView.Mode.valueOf(prefs().getString(KEY_TAB, null) ?: "") }.getOrDefault(FinderView.Mode.RADAR)
        tabs = AncSegmentedView(this, listOf(getString(R.string.find_tab_arrow), getString(R.string.find_tab_radar))).apply {
            selected = mode.ordinal
            onSegmentTapped = { i ->
                mode = FinderView.Mode.values()[i]
                prefs().edit().putString(KEY_TAB, mode.name).apply()
                render()
            }
        }
        root.addView(tabs, lp(8f))
        dial = FinderView(this).apply { hereColor = p.onAccent; mode = this@FindNearbyActivity.mode }
        root.addView(dial, lp(8f))
        arrowStatus = text(0, 14f, R.attr.appColorTextSecondary).apply { minLines = 2 }
        root.addView(arrowStatus, lp(2f))
        againBtn = button(R.string.find_arrow_again, false) {
            clearDirection(); render()
        }.apply { visibility = View.GONE }
        root.addView(againBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(6f)
        })
        // Big, as the distance was before: 40 sp, shrinking to fit one line for a long text.
        headline = text(0, 40f, R.attr.appColorTextPrimary).apply {
            typeface = ThemeRes.headline(this@FindNearbyActivity)
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(20, 40, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        root.addView(headline, lp(4f))
        stateLine = text(0, 18f, R.attr.appColorTextPrimary).apply { typeface = ThemeRes.medium(this@FindNearbyActivity); minLines = 1 }
        root.addView(stateLine, lp(2f))
        subline = text(0, 15f, R.attr.appColorTextPrimary)
        root.addView(subline, lp(2f))
        distanceLine = text(0, 15f, R.attr.appColorTextSecondary).apply { typeface = ThemeRes.medium(this@FindNearbyActivity) }
        root.addView(distanceLine, lp(6f))
        guidance = text(0, 16f, R.attr.appColorAccent).apply { typeface = ThemeRes.medium(this@FindNearbyActivity); minLines = 2 }
        root.addView(guidance, lp(6f))

        scanBtn = button(R.string.find_btn_scan, false) { scanAgain() }
        playBtn = button(R.string.find_play, true) { onPlay() }
        root.addView(row(scanBtn, playBtn), lp(16f))

        graphTitle = text(R.string.find_graph_title, 13f, R.attr.appColorTextSecondary).apply { gravity = Gravity.START }
        root.addView(graphTitle, lp(18f))
        graph = SignalGraphView(this).apply { emptyText = getString(R.string.find_graph_empty) }
        root.addView(graph, lp(4f))
        rawBox = CheckBox(this).apply {
            setText(R.string.find_graph_raw)
            setTextColor(ThemeRes.color(this@FindNearbyActivity, R.attr.appColorTextSecondary))
            setOnCheckedChangeListener { _, on -> graph.showRaw = on }
        }
        root.addView(rawBox, lp(2f))
        summary = text(0, 14f, R.attr.appColorTextSecondary).apply { gravity = Gravity.START; setLineSpacing(0f, 1.15f) }
        root.addView(summary, lp(8f))
        direction = text(R.string.find_radar_note, 12f, R.attr.appColorTextSecondary).apply { gravity = Gravity.START }
        root.addView(direction, lp(10f))
        note = text(R.string.find_nav_note, 12f, R.attr.appColorTextSecondary).apply { gravity = Gravity.START }
        root.addView(note, lp(4f))
        diagnostics = text(0, 11f, R.attr.appColorTextSecondary).apply {
            gravity = Gravity.START; typeface = Typeface.MONOSPACE
            visibility = if (prefs().getBoolean(KEY_DIAGNOSTICS, false)) View.VISIBLE else View.GONE
        }
        root.addView(diagnostics, lp(12f))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(p.background)
            addView(root)
        })
    }

    /** The name to show: from the caller, the model list, the connected devices, Bluetooth; the address last. */
    private fun resolveName(): String = intent.getStringExtra(EXTRA_NAME)
        ?: (if (isBuds) ModelCatalog.current(this)?.name else null)
        ?: ConnectedDevices.all().firstOrNull { it.address == address }?.name
        ?: (if (connectAllowed()) runCatching { adapter()?.getRemoteDevice(address)?.let { AudioDevice.name(it) } }.getOrNull() else null)
        ?: address

    // ==================== Permission ====================

    private fun prefs() = getSharedPreferences(ThemeRes.PREFS_NAME, MODE_PRIVATE)

    private fun askScanPermission() {
        // Refused for good earlier: Android shows no dialog any more, so the app's settings are the only way.
        if (prefs().getBoolean(KEY_SCAN_REFUSED, false)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            return
        }
        requestPermissions(arrayOf(scanPermission()), REQUEST_SCAN)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_SCAN) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            prefs().edit().remove(KEY_SCAN_REFUSED).apply()
            startScan()
        } else if (!shouldShowRequestPermissionRationale(scanPermission())) {
            prefs().edit().putBoolean(KEY_SCAN_REFUSED, true).apply()
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        /** One beat: a connection read and a redraw. */
        private const val READ_MS = 400L
        /** Missed connection reads (about 3 s) before the connection counts as gone. */
        private const val LOST_AFTER = 8
        /** The connection's 1 m level in dBm (phones that report dBm; no level is stated on a connection). */
        private const val LINK_DEFAULT_REFERENCE = -60.0
        private const val HERE_PULSE_MS = 1_000L
        /** Pulses only at the start of the very-close stage. */
        private const val HERE_PULSE_FOR_MS = 10_000L
        private const val KEY_SCAN_REFUSED = "findScanRefused"
        private const val KEY_DIAGNOSTICS = "findDiagnostics"
        private const val KEY_TAB = "findTab"
        /** A found direction holds this long; then the user turns again. */
        private const val BEARING_MS = 45_000L
        /** Last battery levels from the loss card: left, right, case for the buds, one level for another device. */
        const val EXTRA_LEVELS = "levels"
        private const val SCAN_RETRY_MS = 30_000L
        private const val SCAN_RESTART_MS = 10_000L
        private const val REQUEST_SCAN = 51
        private val FAST_PAIR: ParcelUuid = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

        fun intent(context: Context, address: String, name: String?, levels: IntArray? = null): Intent =
            Intent(context, FindNearbyActivity::class.java).putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_NAME, name)
                .apply { if (levels != null) putExtra(EXTRA_LEVELS, levels) }
    }
}
