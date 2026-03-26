package com.openrs.dash.data

import android.content.Context
import org.json.JSONObject

data class ForscanPid(
    val name: String,
    val description: String,
    val unit: String,
    val status: String,
    val did: String,
    val bytes: Int = 0,
    val formula: String = "",
    val field: String = "",
    val pollGroup: String = ""
)

data class ForscanModule(
    val id: String,
    val name: String,
    val canRequestId: String,
    val canResponseId: String,
    val pids: List<ForscanPid>
) {
    val monitoredCount: Int get() = pids.count { it.status == "monitored" }
}

data class ForscanCatalogData(
    val totalPids: Int,
    val monitoredPids: Int,
    val modules: List<ForscanModule>
)

/**
 * Lazy-loaded singleton for the FORScan PID catalog stored in assets/pids/forscan_modules.json.
 *
 * Call [load] once (e.g. from DiagPage composition) before accessing [catalog].
 * Subsequent calls are no-ops.
 */
object ForscanCatalog {

    @Volatile var catalog: ForscanCatalogData? = null
        private set
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                ctx.assets.open("pids/forscan_modules.json").use { stream ->
                    val root = JSONObject(stream.bufferedReader().readText())
                    val totalPids = root.getInt("total_pids")
                    val monitoredPids = root.getInt("monitored_pids")
                    val modulesArr = root.getJSONArray("modules")
                    val modules = (0 until modulesArr.length()).map { i ->
                        val m = modulesArr.getJSONObject(i)
                        val pidsArr = m.getJSONArray("pids")
                        val pids = (0 until pidsArr.length()).map { j ->
                            val p = pidsArr.getJSONObject(j)
                            ForscanPid(
                                name = p.getString("name"),
                                description = p.getString("description"),
                                unit = p.optString("unit", ""),
                                status = p.getString("status"),
                                did = p.optString("did", ""),
                                bytes = p.optInt("bytes", 0),
                                formula = p.optString("formula", ""),
                                field = p.optString("field", ""),
                                pollGroup = p.optString("poll_group", "")
                            )
                        }
                        ForscanModule(
                            id = m.getString("id"),
                            name = m.getString("name"),
                            canRequestId = m.optString("can_request_id", ""),
                            canResponseId = m.optString("can_response_id", ""),
                            pids = pids
                        )
                    }
                    catalog = ForscanCatalogData(totalPids, monitoredPids, modules)
                }
            } catch (e: Exception) {
                android.util.Log.e("ForscanCatalog", "Failed to load catalog", e)
                catalog = ForscanCatalogData(0, 0, emptyList())
            }
            loaded = true
        }
    }
}
