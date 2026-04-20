package com.openrs.dash.diagnostics

import com.openrs.dash.data.FreezeFrame
import com.openrs.dash.data.FreezeFrameEntry

/**
 * Parses UDS freeze frame (snapshot) data from ECU responses.
 *
 * Freeze frame responses from UDS 0x19/05 contain alternating 2-byte DIDs
 * and their variable-length data values. This parser extracts those pairs
 * and decodes known Ford/SAE DIDs into human-readable values.
 */
object FreezeFrameParser {

    /**
     * Common Ford / SAE J1979 freeze frame DIDs with their byte length and decoder.
     * These are the most commonly found DIDs in Ford Focus RS freeze frame records.
     */
    private data class DidDef(
        val label: String,
        val dataLen: Int,
        val decode: (ByteArray) -> String
    )

    private val KNOWN_DIDS: Map<Int, DidDef> = mapOf(
        // SAE J1979 standard PIDs mapped to Mode 22 DID space (0xF4xx)
        0xF400 to DidDef("Engine RPM", 2) { d ->
            val rpm = ((d[0].toInt() and 0xFF) shl 8 or (d[1].toInt() and 0xFF)) / 4
            "$rpm rpm"
        },
        0xF401 to DidDef("Engine Load", 1) { d ->
            "%.1f%%".format((d[0].toInt() and 0xFF) * 100.0 / 255)
        },
        0xF402 to DidDef("Coolant Temp", 1) { d ->
            "${(d[0].toInt() and 0xFF) - 40}°C"
        },
        0xF403 to DidDef("STFT Bank 1", 1) { d ->
            "%.1f%%".format((d[0].toInt() and 0xFF) / 1.28 - 100)
        },
        0xF404 to DidDef("LTFT Bank 1", 1) { d ->
            "%.1f%%".format((d[0].toInt() and 0xFF) / 1.28 - 100)
        },
        0xF405 to DidDef("Fuel Pressure", 1) { d ->
            "${(d[0].toInt() and 0xFF) * 3} kPa"
        },
        0xF406 to DidDef("Intake MAP", 1) { d ->
            "${d[0].toInt() and 0xFF} kPa"
        },
        0xF40C to DidDef("Engine RPM (alt)", 2) { d ->
            val rpm = ((d[0].toInt() and 0xFF) shl 8 or (d[1].toInt() and 0xFF)) / 4
            "$rpm rpm"
        },
        0xF40D to DidDef("Vehicle Speed", 1) { d ->
            "${d[0].toInt() and 0xFF} km/h"
        },
        0xF40E to DidDef("Timing Advance", 1) { d ->
            "%.1f°".format((d[0].toInt() and 0xFF) / 2.0 - 64)
        },
        0xF40F to DidDef("Intake Air Temp", 1) { d ->
            "${(d[0].toInt() and 0xFF) - 40}°C"
        },
        0xF410 to DidDef("MAF Rate", 2) { d ->
            "%.2f g/s".format(((d[0].toInt() and 0xFF) shl 8 or (d[1].toInt() and 0xFF)) / 100.0)
        },
        0xF411 to DidDef("Throttle Pos", 1) { d ->
            "%.1f%%".format((d[0].toInt() and 0xFF) * 100.0 / 255)
        },
        0xF442 to DidDef("ECU Voltage", 2) { d ->
            "%.3f V".format(((d[0].toInt() and 0xFF) shl 8 or (d[1].toInt() and 0xFF)) / 1000.0)
        },
        0xF446 to DidDef("Ambient Temp", 1) { d ->
            "${(d[0].toInt() and 0xFF) - 40}°C"
        },
        // Ford-specific common freeze frame DIDs
        0x0457 to DidDef("Boost Actual", 2) { d ->
            "%.1f kPa".format(((d[0].toInt() and 0xFF) shl 8 or (d[1].toInt() and 0xFF)) * 0.1)
        },
        0x045A to DidDef("Boost Desired", 2) { d ->
            "%.1f kPa".format(((d[0].toInt() and 0xFF) shl 8 or (d[1].toInt() and 0xFF)) * 0.1)
        },
        0x0462 to DidDef("Wastegate DC", 1) { d ->
            "%.1f%%".format((d[0].toInt() and 0xFF) * 100.0 / 255)
        },
    )

    /** Default byte length for unknown DIDs. */
    private const val DEFAULT_DID_LEN = 2

    /**
     * Parse a UDS 0x19/05 response payload into a [FreezeFrame].
     *
     * Response format after stripping UDS headers:
     * [DID_high, DID_low, data_byte(s)...] repeated
     *
     * @param dtcCode The DTC code this freeze frame belongs to
     * @param recordNumber The snapshot record number
     * @param payload Raw response payload after SID/DTC bytes
     */
    fun parse(dtcCode: String, recordNumber: Int, payload: ByteArray): FreezeFrame {
        val entries = mutableListOf<FreezeFrameEntry>()
        var offset = 0

        while (offset + 2 < payload.size) {
            val did = ((payload[offset].toInt() and 0xFF) shl 8) or
                      (payload[offset + 1].toInt() and 0xFF)
            offset += 2

            val def = KNOWN_DIDS[did]
            val dataLen = def?.dataLen ?: minOf(DEFAULT_DID_LEN, payload.size - offset)
            if (offset + dataLen > payload.size) break

            val rawBytes = payload.copyOfRange(offset, offset + dataLen)
            offset += dataLen

            val label = def?.label ?: "DID 0x%04X".format(did)
            val value = try {
                def?.decode?.invoke(rawBytes) ?: rawBytes.joinToString(" ") { "%02X".format(it) }
            } catch (_: Exception) {
                rawBytes.joinToString(" ") { "%02X".format(it) }
            }

            entries += FreezeFrameEntry(
                did = did,
                rawBytes = rawBytes,
                label = label,
                value = value
            )
        }

        return FreezeFrame(dtcCode, recordNumber, entries)
    }

    /**
     * Parse a UDS 0x19/04 response to extract DTC + record number pairs
     * that have available snapshots.
     *
     * Response format: [0x59, 0x04, DTC_high, DTC_mid, DTC_low, record_num, ...]
     */
    fun parseSnapshotIdentification(payload: ByteArray): List<Pair<ByteArray, Int>> {
        if (payload.size < 2) return emptyList()
        if ((payload[0].toInt() and 0xFF) != 0x59) return emptyList()

        val results = mutableListOf<Pair<ByteArray, Int>>()
        var offset = 2  // skip SID + sub-function
        while (offset + 3 < payload.size) {
            val dtcBytes = payload.copyOfRange(offset, offset + 3)
            offset += 3
            if (offset >= payload.size) break
            val recordNum = payload[offset].toInt() and 0xFF
            offset += 1
            // Skip null DTC entries
            if (dtcBytes[0].toInt() == 0 && dtcBytes[1].toInt() == 0) continue
            results += dtcBytes to recordNum
        }
        return results
    }
}
