package com.openrs.dash.can

import java.util.UUID

/**
 * Verified BLE GATT layouts of the supported adapters (#218). The profile is chosen from the services the adapter
 * actually exposes (adapter type only breaks ties) — never a global UUID swap, so each adapter keeps its own layout.
 *
 * Sources (upstream meatpiHQ/wican-fw `main/ble.c`; line references in the private evidence notes):
 *  - WiCAN Pro v4.45p / v4.48p: service FFF0, write FFF2 (write / write-no-response), notify FFF1; both require an
 *    encrypted, MITM-authenticated (bonded) link.
 *  - WiCAN USB v4.13u–v4.21u: service FEE0, FEE1 is both the write and the notify characteristic; encrypted + MITM.
 *  - Legacy FFE0 / FFE1 / FFE2: the openRS_ firmware README layout used by earlier app builds. No shipped firmware
 *    implements it (#67) — kept only as a last fallback, unverified.
 * Source-verified, not yet adapter-verified (no GATT trace from a physical adapter).
 */
data class BleAdapterProfile(
    val name: String,
    val service: UUID,
    val write: UUID,
    val notify: UUID,
    val verified: Boolean,
) {
    companion object {
        private fun u16(v: Int): UUID = UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(v))

        val CCCD: UUID = u16(0x2902)

        val WICAN_PRO = BleAdapterProfile("WiCAN Pro (FFF0)", u16(0xFFF0), write = u16(0xFFF2), notify = u16(0xFFF1), verified = true)
        val WICAN_USB = BleAdapterProfile("WiCAN USB (FEE0)", u16(0xFEE0), write = u16(0xFEE1), notify = u16(0xFEE1), verified = true)
        val LEGACY_FFE0 = BleAdapterProfile("legacy FFE0", u16(0xFFE0), write = u16(0xFFE1), notify = u16(0xFFE2), verified = false)

        val ALL = listOf(WICAN_PRO, WICAN_USB, LEGACY_FFE0)

        /** Services the device scan filters on (any supported adapter). */
        val SCAN_SERVICES: List<UUID> = ALL.map { it.service }

        /**
         * The profile for an adapter exposing [services] (service → its characteristic UUIDs): the first profile whose
         * service *and* both characteristics are present, preferring the one matching [adapterType]
         * ("MEATPI_PRO" / "MEATPI_USB"). Null = no supported layout (the device isn't a supported adapter, or its
         * firmware exposes something else).
         */
        fun select(services: Map<UUID, Set<UUID>>, adapterType: String?): BleAdapterProfile? {
            val preferred = when (adapterType) {
                "MEATPI_PRO" -> listOf(WICAN_PRO, WICAN_USB, LEGACY_FFE0)
                "MEATPI_USB" -> listOf(WICAN_USB, WICAN_PRO, LEGACY_FFE0)
                else -> ALL
            }
            return preferred.firstOrNull { p ->
                val chars = services[p.service] ?: return@firstOrNull false
                p.write in chars && p.notify in chars
            }
        }
    }
}

/**
 * What a GATT status during BLE setup means (#218). Both WiCAN layouts require an encrypted, authenticated link, so an
 * unbonded phone gets an authentication / encryption error on its first subscription — that is a pairing step, not a
 * dead adapter. Any other failure stops setup with a specific message instead of carrying on as if subscribed.
 */
object BleSetupPolicy {
    const val GATT_SUCCESS = 0
    const val GATT_INSUFFICIENT_AUTHENTICATION = 5
    const val GATT_INSUFFICIENT_ENCRYPTION = 15
    /** Android's generic "auth failed" (0x89) reported by some stacks for a refused / failed pairing. */
    const val GATT_AUTH_FAIL = 137

    sealed interface Action {
        data object Proceed : Action
        /** Pair (bond) with the adapter, then repeat the failed write once. */
        data object BondAndRetry : Action
        data class Fail(val message: String) : Action
    }

    /** After the notification subscription (CCCD write) reported [status]. */
    fun afterSubscribe(status: Int, alreadyRetried: Boolean): Action = when {
        status == GATT_SUCCESS -> Action.Proceed
        isAuthError(status) && !alreadyRetried -> Action.BondAndRetry
        isAuthError(status) -> Action.Fail("Pairing didn't complete — accept the pairing request (PIN from the adapter's settings) and connect again (GATT $status)")
        else -> Action.Fail("Couldn't subscribe to the adapter's notifications (GATT $status)")
    }

    /** After a characteristic write reported [status]: null = fine, else the failure to surface. */
    fun afterWrite(status: Int): String? = when {
        status == GATT_SUCCESS -> null
        isAuthError(status) -> "BLE write refused — not paired (GATT $status)"
        else -> "BLE write failed (GATT $status)"
    }

    private fun isAuthError(status: Int) =
        status == GATT_INSUFFICIENT_AUTHENTICATION || status == GATT_INSUFFICIENT_ENCRYPTION || status == GATT_AUTH_FAIL
}
