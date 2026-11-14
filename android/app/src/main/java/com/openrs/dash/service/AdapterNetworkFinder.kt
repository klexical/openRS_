package com.openrs.dash.service

import com.openrs.dash.can.AdapterProbe
import com.openrs.dash.can.ProbeOutcome
import com.openrs.dash.can.SlcanTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Finds the adapter network among the Wi-Fi networks the phone has joined (#324): a Wi-Fi network on which the adapter gave
 * its own protocol reply ([com.openrs.dash.can.AdapterProbe]). Nothing is remembered and nothing assumed about a network's
 * name, subnet or gateway (#210), so a custom host or an adapter on another LAN is found the same way.
 *
 * Each network the Wi-Fi callback reports is probed at once, then every [REPROBE_MS] until [WINDOW_MS] after it appeared
 * (a window per trigger: a later launch or bind starts a new one),
 * because an adapter's access point can be up before its server listens. Probing stops when the network is lost, when
 * [mayProbe] says no (Bluetooth, demo, a hold, a connection already running), or at the first answer, which goes to
 * [onFound] with its open transport (that callback closes it if it does not use it) and this network's probe lines.
 *
 * Logged ([log]): one probe event per probe (the kind of answer or the reason, and its timing; never the host), the first
 * refusal as a refused start, and one "gave up" line at the end of the window. Generic over the network type so it runs on
 * the JVM without Android.
 */
class AdapterNetworkFinder<N : Any>(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val probe: suspend (N) -> ProbeOutcome,
    private val mayProbe: () -> Boolean,
    private val onFound: (N, SlcanTransport, List<String>) -> Unit,
    private val log: (type: String, message: String) -> Unit,
) {
    companion object {
        const val REPROBE_MS = 5_000L
        const val WINDOW_MS = 60_000L
        const val EVENT_PROBE = "ADAPTER_PROBE"
        const val EVENT_REFUSED = "LINK_START_REFUSED"   // DiagnosticLogger.EVENT_LINK_START_REFUSED
    }

    private val jobs = HashMap<N, Job>()
    private val ids = HashMap<N, Int>()
    private var nextId = 1
    private var found: N? = null

    /** The network that last passed, while it is still joined: a manual Connect and a retry use it. */
    val adapterNetwork: N? get() = synchronized(this) { found }

    /** A Wi-Fi network was reported. Probing starts unless this network is already being probed. */
    fun networkAvailable(network: N) = synchronized(this) {
        if (jobs[network]?.isActive == true) return@synchronized
        val id = ids.getOrPut(network) { nextId++ }
        jobs[network] = scope.launch { run(network, id) }
    }

    /** A Wi-Fi network went away: its probing stops, and it is no longer the adapter network. */
    fun networkLost(network: N) = synchronized(this) {
        jobs.remove(network)?.cancel()
        if (found == network) found = null
    }

    fun stopAll() = synchronized(this) { jobs.values.forEach { it.cancel() }; jobs.clear() }

    private suspend fun run(network: N, id: Int) {
        val appeared = clock()
        val lines = mutableListOf<String>()
        var probes = 0
        var refusalLogged = false
        var lastReason = ""
        while (true) {
            if (!mayProbe()) return
            probes++
            val at = clock() - appeared
            // A probe that throws is a refused probe, never a probing job that dies silently
            val outcome = try { probe(network) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ProbeOutcome.NoAnswer(AdapterProbe.reasonFor(e, timedOut = false), 0L)
            }
            when (val o = outcome) {
                is ProbeOutcome.Answered -> {
                    // A loss or a stop that landed while the probe ran: the answer is closed, never handed over (C1)
                    if (!currentCoroutineContext().isActive) { o.transport.close(); return }
                    lines += "network $id probe $probes at +${at} ms: answer=${o.answer} in ${o.ms} ms"
                    log(EVENT_PROBE, lines.last())
                    synchronized(this) { found = network }
                    onFound(network, o.transport, lines.toList())
                    return
                }
                is ProbeOutcome.NoAnswer -> {
                    lastReason = o.reason
                    lines += "network $id probe $probes at +${at} ms: no answer in ${o.ms} ms (${o.reason})"
                    log(EVENT_PROBE, lines.last())
                    if (!refusalLogged) {
                        log(EVENT_REFUSED, "not started (automatic start): not an adapter network yet: ${o.reason}; probing every " +
                            "${REPROBE_MS / 1000} s for up to ${WINDOW_MS / 1000} s")
                        refusalLogged = true
                    }
                }
            }
            val next = appeared + probes * REPROBE_MS
            if (next - appeared > WINDOW_MS) {
                // The line that survives into the next session (carried), so it keeps the last reason (A5)
                log(EVENT_REFUSED, "not started (automatic start): no adapter network: gave up after ${WINDOW_MS / 1000} s " +
                    "($probes probes; the last: $lastReason)")
                return
            }
            delay((next - clock()).coerceAtLeast(0L))
        }
    }
}

/**
 * The probe's open transport on its way to a connection (#324). One rule: a hand-over no connection uses is closed. The
 * connection's first attempt [take]s it; [adopt] marks it as owned by a connection being built (whose teardown then calls
 * [closeIfUntaken]); the start path's `finally` calls [closeUnlessAdopted]. Closed at most once.
 */
class HandOver(private val transport: com.openrs.dash.can.SlcanTransport) {
    private var taken = false
    private var adopted = false

    @Synchronized fun take(): com.openrs.dash.can.SlcanTransport? = if (taken) null else { taken = true; transport }
    @Synchronized fun adopt() { adopted = true }
    @Synchronized fun closeIfUntaken() { if (!taken) { taken = true; transport.close() } }
    @Synchronized fun closeUnlessAdopted() { if (!adopted) closeIfUntaken() }
}
