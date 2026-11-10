package com.openrs.dash.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openrs.dash.data.ObserverNote
import com.openrs.dash.ui.Bg
import com.openrs.dash.ui.Brd
import com.openrs.dash.ui.Frost
import com.openrs.dash.ui.InstrumentFill
import com.openrs.dash.ui.LocalThemeAccent
import com.openrs.dash.ui.Mid
import com.openrs.dash.ui.MonoText
import com.openrs.dash.ui.ShareTechMono
import com.openrs.dash.ui.Surf2
import com.openrs.dash.ui.Tokens
import com.openrs.dash.ui.actionInk

/** The note form in its own dialog window (long-press on TRIP's MARK while recording). */
@Composable
internal fun ObserverNoteDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ObserverNoteForm(onSave, onDismiss, Modifier.fillMaxWidth(0.92f))
    }
}

/**
 * A bookmark with words: one tap on what the cluster showed ([ObserverNote.PRESETS]) saves at once, or a short text and
 * SAVE. The note is the observer's word; the form says where it goes (the drive and its exports).
 */
@Composable
internal fun ObserverNoteForm(onSave: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    val accent = LocalThemeAccent.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.background(Bg, shape).border(Tokens.CardBorder, Brd, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MonoText("OBSERVER NOTE", 13.sp, Frost, FontWeight.Bold)
        MonoText("What you saw, marked at this moment. Saved with the drive and included in its exports.", 11.sp, Mid)
        ObserverNote.PRESETS.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { preset -> NoteButton(preset, Frost, Modifier.weight(1f)) { onSave(preset) } }
            }
        }
        BasicTextField(
            value = text,
            onValueChange = { text = it.replace('\n', ' ').take(ObserverNote.MAX) },
            singleLine = true,
            textStyle = TextStyle(fontFamily = ShareTechMono, fontSize = 13.sp, color = Frost),
            cursorBrush = SolidColor(accent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note text" },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Surf2, RoundedCornerShape(8.dp))
                        .border(Tokens.CardBorder, Brd, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isEmpty()) MonoText("Other, up to ${ObserverNote.MAX} characters", 12.sp, Mid)
                    inner()
                }
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NoteButton("CANCEL", Mid, Modifier.weight(1f), onCancel)
            NoteButton("SAVE NOTE", actionInk(accent), Modifier.weight(1f)) { onSave(ObserverNote.clean(text)) }
        }
    }
}

@Composable
private fun NoteButton(label: String, ink: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape).background(InstrumentFill, shape).border(Tokens.CardBorder, Brd, shape)
            .clickable(onClickLabel = label) { onClick() }.padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) { MonoText(label, 12.sp, ink, FontWeight.Bold) }
}
