#!/usr/bin/env python3
"""Parse forscan-available-pids.csv into forscan_modules.json for the Android app."""

import json
import re
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CSV_PATH = os.path.join(SCRIPT_DIR, "..", "docs", "forscan-available-pids.csv")
OUT_PATH = os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets", "pids", "forscan_modules.json")

# Module boundaries: (start_line, end_line) inclusive, 1-indexed matching CSV line numbers
MODULES = [
    ("ABS",  "Anti-Lock Brake System",          1,   60,  None,    None),
    ("ACM",  "Audio Control Module",            61,   78,  None,    None),
    ("APIM", "Accessory Protocol Interface",    79,   95,  None,    None),
    ("AWD",  "All-Wheel Drive Module",          96,  138,  "0x703", "0x70B"),
    ("BCM",  "Body Control Module",            139,  344,  "0x726", "0x72E"),
    ("DDM",  "Driver Door Module",             345,  377,  None,    None),
    ("FCIM", "Front Controls Interface",       378,  387,  None,    None),
    ("GFM2", "Generic Function Module",        388,  399,  None,    None),
    ("HCM",  "Height Control Module",          400,  408,  None,    None),
    ("HVAC", "Climate Control Module",         409,  434,  "0x733", "0x73B"),
    ("ICM",  "Instrument Cluster Module",      435,  439,  None,    None),
    ("IPC",  "Instrument Panel Cluster",       440,  544,  "0x720", "0x728"),
    ("OBDII","Standard OBD-II",                545,  577,  "0x7E0", "0x7E8"),
    ("PAM",  "Parking Aid Module",             578,  607,  None,    None),
    ("PCM",  "Powertrain Control Module",      608, 1055,  "0x7E0", "0x7E8"),
    ("PDM",  "Passenger Door Module",         1056, 1079,  None,    None),
    ("PSCM", "Power Steering Control Module", 1080, 1092,  "0x730", "0x738"),
    ("RCM",  "Restraints Control Module",     1093, 1116,  None,    None),
    ("SCCM", "Steering Column Control Module",1117, 1133,  None,    None),
    ("SASM", "Steering Angle Sensor Module",  1134, 1148,  None,    None),
]

# FORScan PID name → (DID hex, module) for PIDs the app actively queries.
# Only first occurrence per module is matched (some names repeat across modules).
MONITORED = {
    # PCM (0x7E0)
    ("PCM",  "ETC_ACT"):        "0x093C",
    ("PCM",  "ETC_DSD"):        "0x091A",
    ("PCM",  "TURBO_WGATE"):    "0x0462",
    ("PCM",  "KNK_CNTR_CYL1"):  "0x03EC",
    ("PCM",  "KNK_CNTR_CYL2"):  "0x03ED",
    ("PCM",  "KNK_CNTR_CYL3"):  "0x03EE",
    ("PCM",  "KNK_CNTR_CYL4"):  "0x03EF",
    ("PCM",  "OCTADJ_R_LRND"):  "0x03E8",
    ("PCM",  "CAC_T"):          "0x0461",
    ("PCM",  "CATEMP11"):       "0xF43C",
    ("PCM",  "EQ_RAT11"):       "0xF434",
    ("PCM",  "EQRAT11_CMD"):    "0xF444",
    ("PCM",  "TCBP"):           "0x033E",
    ("PCM",  "TCBP_DSD"):       "0x0466",
    ("PCM",  "VCT_INTK_DSD"):   "0x0318",
    ("PCM",  "VCT_EXH_DSD"):    "0x0319",
    ("PCM",  "OIL_REMAINING"):   "0x054B",
    ("PCM",  "FRP"):            "0xF422",
    ("PCM",  "FLI"):            "0xF42F",
    ("PCM",  "VPWR"):           "0x0304",
    # BCM (0x726)
    ("BCM",  "BAT_ST_CHRG"):    "0x4028",
    ("BCM",  "BAT_TEMP"):       "0x4029",
    ("BCM",  "IN-CAR_TEMP"):    "0xDD04",
    ("BCM",  "TPM_PRES_LF"):    "0x2813",
    ("BCM",  "TPM_PRES_RF"):    "0x2814",
    ("BCM",  "TPM_PRES_LRO"):   "0x2816",
    ("BCM",  "TPM_PRES_RRO"):   "0x2815",
    ("BCM",  "TPM_S_ID_LF"):    "0x280F",
    ("BCM",  "TPM_S_ID_RF"):    "0x2810",
    ("BCM",  "TPM_S_ID_LRO"):   "0x2812",
    ("BCM",  "TPM_S_ID_RRO"):   "0x2811",
    ("BCM",  "TOTAL_DIST"):     "0xDD01",
    # AWD (0x703)
    ("AWD",  "R_DIFF_OIL_TMP"): "0x1E8A",
}

# Extended decode info for monitored PIDs: (module, DID) → {bytes, formula, field, poll_group}
# formula uses A, B, C for data bytes; signed(x) for two's complement
# Complex multi-field decoders (TPMS, odometer) are omitted — handled in Kotlin.
DECODE_INFO = {
    # PCM standard poll group
    ("PCM", "0x093C"):  {"bytes": 2, "formula": "(A*256+B)*(100.0/8192.0)",       "field": "etcAngleActual",   "poll_group": "pcm_standard"},
    ("PCM", "0x091A"):  {"bytes": 2, "formula": "(A*256+B)*(100.0/8192.0)",       "field": "etcAngleDesired",  "poll_group": "pcm_standard"},
    ("PCM", "0x0462"):  {"bytes": 1, "formula": "A*100.0/128.0",                  "field": "wgdcDesired",      "poll_group": "pcm_standard"},
    ("PCM", "0x03EC"):  {"bytes": 2, "formula": "signed(A*256+B)/-512.0",         "field": "ignCorrCyl1",      "poll_group": "pcm_standard"},
    ("PCM", "0x03ED"):  {"bytes": 2, "formula": "signed(A*256+B)/-512.0",         "field": "ignCorrCyl2",      "poll_group": "pcm_standard"},
    ("PCM", "0x03EE"):  {"bytes": 2, "formula": "signed(A*256+B)/-512.0",         "field": "ignCorrCyl3",      "poll_group": "pcm_standard"},
    ("PCM", "0x03EF"):  {"bytes": 2, "formula": "signed(A*256+B)/-512.0",         "field": "ignCorrCyl4",      "poll_group": "pcm_standard"},
    ("PCM", "0x03E8"):  {"bytes": 2, "formula": "signed(A*256+B)/16384.0",        "field": "octaneAdjustRatio","poll_group": "pcm_standard"},
    ("PCM", "0x0461"):  {"bytes": 2, "formula": "signed(A*256+B)/64.0",           "field": "chargeAirTempC",   "poll_group": "pcm_standard"},
    ("PCM", "0xF43C"):  {"bytes": 2, "formula": "(A*256+B)/10.0-40.0",            "field": "catalyticTempC",   "poll_group": "pcm_standard"},
    ("PCM", "0xF434"):  {"bytes": 2, "formula": "(A*256+B)*0.0004486",            "field": "afrActual",        "poll_group": "pcm_standard"},
    ("PCM", "0xF444"):  {"bytes": 1, "formula": "A*0.1144",                       "field": "afrDesired",       "poll_group": "pcm_standard"},
    ("PCM", "0x033E"):  {"bytes": 2, "formula": "(A*256+B)/903.81",               "field": "tipActualKpa",     "poll_group": "pcm_standard"},
    ("PCM", "0x0466"):  {"bytes": 2, "formula": "(A*256+B)/903.81",               "field": "tipDesiredKpa",    "poll_group": "pcm_standard"},
    ("PCM", "0x0318"):  {"bytes": 2, "formula": "signed(A*256+B)/16.0",           "field": "vctIntakeAngle",   "poll_group": "pcm_standard"},
    ("PCM", "0x0319"):  {"bytes": 2, "formula": "signed(A*256+B)/16.0",           "field": "vctExhaustAngle",  "poll_group": "pcm_standard"},
    ("PCM", "0x054B"):  {"bytes": 1, "formula": "A",                              "field": "oilLifePct",       "poll_group": "pcm_standard"},
    ("PCM", "0xF422"):  {"bytes": 2, "formula": "(A*256+B)*1.45038",              "field": "hpFuelRailPsi",    "poll_group": "pcm_standard"},
    ("PCM", "0xF42F"):  {"bytes": 1, "formula": "A*100.0/255.0",                  "field": "fuelLevelPct",     "poll_group": "pcm_standard"},
    ("PCM", "0x0304"):  {"bytes": 2, "formula": "(A*256+B)/2048.0",               "field": "batteryVoltage",   "poll_group": "pcm_standard"},
    # BCM poll group
    ("BCM", "0x4028"):  {"bytes": 1, "formula": "A",                              "field": "batterySoc",       "poll_group": "bcm_standard"},
    ("BCM", "0x4029"):  {"bytes": 1, "formula": "A-40.0",                         "field": "batteryTempC",     "poll_group": "bcm_standard"},
    ("BCM", "0xDD04"):  {"bytes": 1, "formula": "A*10.0/9.0-45.0",               "field": "cabinTempC",       "poll_group": "bcm_standard"},
    # AWD poll group
    ("AWD", "0x1E8A"):  {"bytes": 1, "formula": "A-40.0",                         "field": "rduTempC",         "poll_group": "awd_standard"},
}


def parse_line(line: str):
    """Parse a tab-separated line into (name, unit, description)."""
    parts = line.split("\t")
    if len(parts) < 3:
        return None
    name = parts[0].strip()
    unit = parts[1].strip()
    desc = parts[2].strip()
    if not name:
        return None
    return name, unit, desc


def main():
    with open(CSV_PATH, "r", encoding="utf-8") as f:
        raw_lines = f.readlines()

    # Parse all CSV lines (1-indexed)
    parsed = {}  # line_number -> (name, unit, desc)
    for i, line in enumerate(raw_lines, start=1):
        result = parse_line(line)
        if result:
            parsed[i] = result

    modules_out = []
    total_pids = 0
    total_monitored = 0

    for mod_id, mod_name, start, end, req_id, resp_id in MODULES:
        pids = []
        for line_num in range(start, end + 1):
            if line_num not in parsed:
                continue
            name, unit, desc = parsed[line_num]
            key = (mod_id, name)
            pid_obj = {"name": name, "description": desc}
            if unit:
                pid_obj["unit"] = unit
            if key in MONITORED:
                pid_obj["status"] = "monitored"
                did_hex = MONITORED[key]
                pid_obj["did"] = did_hex
                decode_key = (mod_id, did_hex)
                if decode_key in DECODE_INFO:
                    info = DECODE_INFO[decode_key]
                    pid_obj["bytes"] = info["bytes"]
                    pid_obj["formula"] = info["formula"]
                    pid_obj["field"] = info["field"]
                    pid_obj["poll_group"] = info["poll_group"]
                total_monitored += 1
            else:
                pid_obj["status"] = "available"
            pids.append(pid_obj)

        mod_obj = {"id": mod_id, "name": mod_name, "pid_count": len(pids)}
        if req_id:
            mod_obj["can_request_id"] = req_id
        if resp_id:
            mod_obj["can_response_id"] = resp_id
        mod_obj["pids"] = pids
        modules_out.append(mod_obj)
        total_pids += len(pids)

    catalog = {
        "version": 1,
        "source": "FORScan Extended PID List — Focus RS MK3",
        "total_pids": total_pids,
        "monitored_pids": total_monitored,
        "modules": modules_out,
    }

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(catalog, f, indent=2, ensure_ascii=False)

    print(f"Wrote {OUT_PATH}")
    print(f"  {len(modules_out)} modules, {total_pids} PIDs, {total_monitored} monitored")


if __name__ == "__main__":
    main()
