package com.openrs.dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The switch's name in the app and in GLOSSARY.md (owner, 2026-11-14, #209). */
const val REQUEST_CAPTURE_LABEL = "Request capture"
/** The switch's text, exactly as the owner set it (2026-11-14, #209). */
const val REQUEST_CAPTURE_TEXT =
    "Capture requests one at a time (this connection only): PCM readings update more slowly while it is on."

/**
 * GARAGE → DIAG, beside the DID prober (#209 Step B): the Request capture switch. [on] is the connection's state (it goes
 * off by itself when the link drops or reconnects); [enabled] is false with no live connection, where the switch can't
 * turn on. A refused change leaves the switch as it is.
 */
@Composable
fun RequestCaptureRow(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(REQUEST_CAPTURE_LABEL, fontSize = 13.sp, color = Frost, fontFamily = ShareTechMono,
                modifier = Modifier.weight(1f))
            Switch(
                checked = on,
                onCheckedChange = onChange,
                enabled = enabled,
                modifier = Modifier.testTag(REQUEST_CAPTURE_SWITCH_TAG),
                colors = SwitchDefaults.colors(
                    checkedThumbColor    = OnAccent,
                    checkedTrackColor    = LocalThemeAccent.current,
                    checkedBorderColor   = LocalThemeAccent.current,
                    uncheckedThumbColor  = Mid,
                    uncheckedTrackColor  = Surf3,
                    uncheckedBorderColor = Mid.copy(alpha = 0.6f),
                ),
            )
        }
        Text(REQUEST_CAPTURE_TEXT, fontSize = 11.sp, color = Dim, fontFamily = ShareTechMono)
    }
}

internal const val REQUEST_CAPTURE_SWITCH_TAG = "request_capture_switch"

/** What DIAG says when the switch refuses a tap (no live connection at that moment, #209). */
const val REQUEST_CAPTURE_REFUSED = "Request capture needs a live connection."
