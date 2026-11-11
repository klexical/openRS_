package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Offline replay through production code (rc.8 step 4; SYNTHETIC fixtures only). A fixture is a list of timed rows:
 *
 *     +<ms> RX <slcan line>    wire traffic: fed to the app exactly as an adapter sends it (no CR)
 *     +<ms> REQ <text>         an app action the test performs at that time (the test reads these itself)
 *     +<ms> NOTE <text>        a user annotation — kept, NEVER fed to the app and never used as evidence
 *     # comment / blank        ignored
 *
 * [ReplayTransport] replays the RX rows (relative to the transport opening) into the production [SlcanConnection] on
 * virtual time; CAN frames go through [LinkSession.onCanFrame] (decode, stamp, peaks — what CanDataService's collector
 * calls) and OBD replies through [ObdMerge.merge] (what CanDataService.mergeObdState does), both into one state flow.
 */
internal data class ReplayRow(val atMs: Long, val kind: String, val text: String)

internal object ReplayFixture {
    private val ROW = Regex("""\+(\d+)\s+(RX|REQ|NOTE)\s+(.*)""")

    fun parse(text: String): List<ReplayRow> = text.lines().mapNotNull { raw ->
        val line = raw.substringBefore(" #").trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val m = ROW.matchEntire(line) ?: error("bad fixture row: $line")
        ReplayRow(m.groupValues[1].toLong(), m.groupValues[2], m.groupValues[3].trim())
    }

    fun load(name: String): List<ReplayRow> =
        parse(requireNotNull(ReplayFixture::class.java.getResource("/replay/$name")) { "no fixture $name" }.readText())
}

internal class ReplayTransport(private val scope: CoroutineScope, private val rows: List<ReplayRow>) : SlcanTransport {
    private val lines = Channel<String>(Channel.UNLIMITED)
    val writes = mutableListOf<String>()
    override suspend fun open() {
        for (r in rows.filter { it.kind == "RX" }) scope.launch { delay(r.atMs); lines.send(r.text) }
    }
    override suspend fun readLine(): String? = lines.receive()
    override suspend fun writeLine(frame: String) { writes += frame }
    override fun close() { lines.close() }
    override val label = "replay"
    override val stockFirmwareLabel = "replay"
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ReplayRun(scope: TestScope, rows: List<ReplayRow>, rpm: Double = 0.0, mapPoll: () -> Boolean = { false }) {
    val state = MutableStateFlow(VehicleState(isConnected = true))
    val transport = ReplayTransport(scope.backgroundScope, rows)
    private val link = LinkOwnership()
    private val session = LinkSession(link, link.begin(), state, NoRecorder, onFrameCounted = {}, perfRunSink = { _: PerfRunEntity -> })
    val connection = SlcanConnection(
        transportFactory = { transport }, maxRetries = 1, rpmProvider = { rpm }, mapPoll = mapPoll,
        ioDispatcher = StandardTestDispatcher(scope.testScheduler), debugSink = {}, clock = { scope.testScheduler.currentTime },
    )

    init {
        scope.backgroundScope.launch {
            connection.connectHybrid({ obd -> state.value = ObdMerge.merge(state.value, obd) }, { state.value })
                .collect { (id, data) -> session.onCanFrame(id, data, 0.0) }
        }
    }

    private object NoRecorder : LinkSession.Recorder {
        override val activeDriveId: Long? = null
    }
}
