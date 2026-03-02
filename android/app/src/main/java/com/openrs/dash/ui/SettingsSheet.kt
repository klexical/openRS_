package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var host by remember { mutableStateOf(AppSettings.getHost(ctx)) }
    var port by remember { mutableStateOf(AppSettings.getPort(ctx).toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Surf, RoundedCornerShape(12.dp))
                .border(1.dp, Brd, RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            // Title
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("open", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    fontFamily = Mono, color = Color(0xFFF5F6F4))
                Text("RS", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    fontFamily = Mono, color = Accent)
                Text("_ Settings", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    fontFamily = Mono, color = Color(0xFFF5F6F4))
            }
            Spacer(Modifier.height(16.dp))

            // WiCAN section header
            Text("WICAN CONNECTION", fontSize = 10.sp, color = Dim,
                fontFamily = Mono, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))

            // Host field
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; error = null },
                label = { Text("Host / IP Address", fontFamily = Mono, fontSize = 11.sp) },
                placeholder = { Text(AppSettings.DEFAULT_HOST, fontFamily = Mono) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = outlinedFieldColors(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = Mono, fontSize = 14.sp, color = Txt
                )
            )
            Spacer(Modifier.height(8.dp))

            // Port field
            OutlinedTextField(
                value = port,
                onValueChange = { port = it; error = null },
                label = { Text("Port", fontFamily = Mono, fontSize = 11.sp) },
                placeholder = { Text(AppSettings.DEFAULT_PORT.toString(), fontFamily = Mono) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = outlinedFieldColors(),
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = Mono, fontSize = 14.sp, color = Txt
                )
            )

            // Error
            if (error != null) {
                Spacer(Modifier.height(4.dp))
                Text(error!!, fontSize = 11.sp, color = Red, fontFamily = Mono)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Default: ${AppSettings.DEFAULT_HOST}:${AppSettings.DEFAULT_PORT}",
                fontSize = 10.sp, color = Dim, fontFamily = Mono
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Brd)
            Spacer(Modifier.height(16.dp))

            // Buttons
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Cancel
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Dim)
                ) {
                    Text("CANCEL", fontFamily = Mono, fontSize = 12.sp)
                }

                // Save
                Button(
                    onClick = {
                        val p = port.toIntOrNull()
                        when {
                            host.isBlank() -> error = "Host cannot be empty"
                            p == null || p !in 1..65535 -> error = "Port must be 1–65535"
                            else -> {
                                AppSettings.save(ctx, host, p)
                                onDismiss()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent, contentColor = Color(0xFF0A0A0A)
                    )
                ) {
                    Text("SAVE", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor    = Accent,
    unfocusedBorderColor  = Brd,
    focusedLabelColor     = Accent,
    unfocusedLabelColor   = Dim,
    cursorColor           = Accent,
    focusedTextColor      = Txt,
    unfocusedTextColor    = Txt,
)
