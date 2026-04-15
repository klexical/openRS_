package com.openrs.dash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Tokens.CardGap
import com.openrs.dash.ui.Tokens.PagePad

/**
 * PERF tab — tuning + dynamics surface.
 *
 * Phase 2: thin composition over [PowerPage]'s AFR/throttle/ignition/fuel-trim
 * content plus [GForceSection] (G-force plot + peak controls). The dedicated
 * CHASSIS tab remains available for tires/AWD detail and will be folded into
 * THERMAL / this page as Phase 3 lands.
 */
@Composable
fun PerfPage(vs: VehicleState, p: UserPrefs, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = PagePad, end = PagePad, top = PagePad, bottom = PagePad + Tokens.NavBarHeight),
        verticalArrangement = Arrangement.spacedBy(CardGap)
    ) {
        GForceSection(vs, onReset)
        PowerPageContent(vs, p)
    }
}
