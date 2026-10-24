package com.victor.signalscanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NfcTab(taps: List<NfcTap>, st: NfcState, wide: Boolean, now: Long) {
    var sel by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = taps.firstOrNull { it.id == sel }
    LaunchedEffect(taps.firstOrNull()?.id) { taps.firstOrNull()?.let { if (wide) sel = it.id } }
    ListDetail(wide, selected != null, { sel = null }, list = {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) {
                    val (msg, col) = when {
                        !st.supported -> "This phone has no NFC hardware" to Muted
                        !st.enabled -> "NFC is turned off – enable it in Settings › Connections › NFC and contactless payments" to Red
                        st.readerActive -> "Ready – hold a card, tag or phone against the back of the phone" to Green
                        else -> "Reader mode is off (Settings) – Android will handle taps normally" to Amber
                    }
                    Text("NFC", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(msg, color = col, fontSize = 14.sp)
                    if (st.supported) Text("Secure NFC: ${if (!st.secureSupported) "not supported" else if (st.secureEnabled) "on" else "off"} · Card emulation: ${if (st.hce) "yes" else "no"}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Text("Only identification data is read (UID, technology, NDEF). Bank cards are not queried for card numbers.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (taps.isEmpty()) item { Text("Tap log is empty", color = Muted, modifier = Modifier.padding(20.dp)) }
            items(taps, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().background(if (t.id == sel) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
                    .clickable { sel = t.id }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(t.guess, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("UID ${t.uid}", color = Muted, fontSize = 12.sp)
                        Row { t.techs.take(4).forEach { Pill(it, Cyan) }; if (t.ndef.isNotEmpty()) Pill("NDEF", Green) }
                    }
                    Text("${Fmt.time(t.time)}\n${Fmt.ago(now - t.time)} ago", color = Muted, fontSize = 11.sp)
                }
            }
        }
    }, detail = {
        selected?.let { t ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                DetailHeader(t.guess, "Tapped ${Fmt.dateTime(t.time)}") { sel = null }
                Section("Tag") { t.fields.forEach { KV(it.first, it.second, mono = it.first in setOf("UID", "ATQA", "Historical bytes", "GET_VERSION", "Application data", "Protocol info")) } }
                if (t.ndef.isNotEmpty()) Section("NDEF records") { t.ndef.forEach { KV(it.first, it.second) } }
                Spacer(Modifier.height(30.dp))
            }
        }
    }, empty = "Tap a card to the phone")
}
