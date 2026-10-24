package com.victor.signalscanner

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.*

data class NfcTap(
    val id: Long,
    val time: Long,
    val uid: String,
    val uidLen: Int,
    val techs: List<String>,
    val guess: String,
    val fields: List<Pair<String, String>>,
    val ndef: List<Pair<String, String>>,
)

/** Reads only the data the reader mode already collected plus harmless identification commands. */
object NfcParse {
    private fun Byte.u() = toInt() and 0xFF
    private fun hex(b: ByteArray?) = b?.joinToString(" ") { "%02X".format(it.u()) } ?: "—"
    private var seq = 0L

    fun read(tag: Tag): NfcTap {
        val f = ArrayList<Pair<String, String>>()
        val nd = ArrayList<Pair<String, String>>()
        val techs = tag.techList.map { it.substringAfterLast('.') }
        var guess = ""
        val uid = tag.id ?: ByteArray(0)
        f += "UID" to hex(uid) + "  (${uid.size} bytes${if (uid.size == 4 && uid[0].u() == 0x08) ", random ID" else ""})"
        f += "Technologies" to techs.joinToString(", ")

        NfcA.get(tag)?.let { a ->
            val atqa = a.atqa; val sak = a.sak.toInt()
            f += "Standard" to "ISO 14443-3A (NFC-A)"
            f += "ATQA" to hex(atqa)
            f += "SAK" to "0x%02X".format(sak)
            f += "Max transceive (NfcA)" to "${a.maxTransceiveLength} bytes"
            guess = guessFromSak(atqa, sak)
            if (uid.isNotEmpty()) f += "Manufacturer (UID)" to (icMakers[uid[0].u()] ?: "code 0x%02X".format(uid[0].u()))
            // NTAG/Ultralight GET_VERSION (read-only identification)
            if (sak == 0x00 && techs.contains("MifareUltralight")) runCatching {
                a.connect(); val v = a.transceive(byteArrayOf(0x60)); a.close()
                if (v.size >= 8) {
                    f += "GET_VERSION" to hex(v)
                    val size = v[6].u()
                    guess = when {
                        v[2].u() == 4 && size == 0x0F -> "NXP NTAG213 (144 bytes user memory)"
                        v[2].u() == 4 && size == 0x11 -> "NXP NTAG215 (504 bytes user memory)"
                        v[2].u() == 4 && size == 0x13 -> "NXP NTAG216 (888 bytes user memory)"
                        v[2].u() == 3 && size == 0x0B -> "MIFARE Ultralight EV1 (48 bytes)"
                        v[2].u() == 3 && size == 0x0E -> "MIFARE Ultralight EV1 (128 bytes)"
                        v[2].u() == 4 -> "NXP NTAG (size code 0x%02X)".format(size)
                        else -> guess
                    }
                }
            }.also { runCatching { a.close() } }
        }
        NfcB.get(tag)?.let { b ->
            f += "Standard" to "ISO 14443-3B (NFC-B)"
            f += "Application data" to hex(b.applicationData)
            f += "Protocol info" to hex(b.protocolInfo)
            f += "Max transceive (NfcB)" to "${b.maxTransceiveLength} bytes"
            if (guess.isEmpty()) guess = "ISO 14443-B card (e.g. ID/transport card)"
        }
        NfcF.get(tag)?.let { x ->
            f += "Standard" to "JIS 6319-4 (FeliCa / NFC-F)"
            f += "Manufacturer (PMm)" to hex(x.manufacturer)
            f += "System code" to hex(x.systemCode)
            f += "Max transceive (NfcF)" to "${x.maxTransceiveLength} bytes"
            if (guess.isEmpty()) guess = "Sony FeliCa"
        }
        NfcV.get(tag)?.let { v ->
            f += "Standard" to "ISO 15693 (NFC-V / vicinity)"
            f += "DSFID" to "0x%02X".format(v.dsfId.u())
            f += "Response flags" to "0x%02X".format(v.responseFlags.u())
            f += "Max transceive (NfcV)" to "${v.maxTransceiveLength} bytes"
            if (uid.size == 8) f += "Manufacturer (UID)" to (icMakers[uid[6].u()] ?: "code 0x%02X".format(uid[6].u()))
            if (guess.isEmpty()) guess = "ISO 15693 tag (e.g. ICODE SLIX, library/ski tag)"
        }
        IsoDep.get(tag)?.let { d ->
            f += "ISO-DEP" to "ISO 14443-4 (smart card protocol)"
            d.historicalBytes?.let { f += "Historical bytes" to hex(it) + asciiHint(it) }
            d.hiLayerResponse?.let { f += "Hi-layer response" to hex(it) }
            f += "Extended-length APDUs" to if (d.isExtendedLengthApduSupported) "yes" else "no"
            f += "Max transceive (IsoDep)" to "${d.maxTransceiveLength} bytes"
            f += "Timeout" to "${d.timeout} ms"
        }
        MifareClassic.get(tag)?.let { m ->
            val type = when (m.type) { MifareClassic.TYPE_CLASSIC -> "Classic"; MifareClassic.TYPE_PLUS -> "Plus"; MifareClassic.TYPE_PRO -> "Pro"; else -> "unknown" }
            f += "MIFARE type" to "MIFARE $type"
            f += "MIFARE size" to "${m.size} bytes, ${m.sectorCount} sectors, ${m.blockCount} blocks"
            guess = "MIFARE $type ${if (m.size >= 1024) "${m.size / 1024}K" else "${m.size} B"}"
        }
        MifareUltralight.get(tag)?.let { m ->
            f += "MIFARE type" to when (m.type) { MifareUltralight.TYPE_ULTRALIGHT -> "Ultralight"; MifareUltralight.TYPE_ULTRALIGHT_C -> "Ultralight C"; else -> "Ultralight (unknown variant)" }
            if (guess.isEmpty()) guess = "MIFARE Ultralight family"
        }
        NdefFormatable.get(tag)?.let { f += "NDEF formatable" to "yes (blank, can be formatted for NDEF)" }
        Ndef.get(tag)?.let { n ->
            f += "NDEF type" to (n.type ?: "?").substringAfterLast('.')
            f += "NDEF capacity" to "${n.maxSize} bytes"
            f += "Writable" to if (n.isWritable) "yes" else "no (locked)"
            f += "Can be made read-only" to if (n.canMakeReadOnly()) "yes" else "no"
            val msg: NdefMessage? = n.cachedNdefMessage
            if (msg == null) nd += "NDEF" to "empty"
            else {
                f += "NDEF message size" to "${msg.byteArrayLength} bytes, ${msg.records.size} record(s)"
                msg.records.forEachIndexed { i, r -> nd += "Record ${i + 1}: ${tnfName(r)}" to describeRecord(r) }
            }
        }
        if (guess.isEmpty()) guess = when {
            techs.contains("IsoDep") -> "ISO 14443-4 smart card"
            else -> "NFC tag"
        }
        return NfcTap(++seq, System.currentTimeMillis(), hex(uid), uid.size, techs, guess, f, nd)
    }

    private fun asciiHint(b: ByteArray): String {
        val s = b.map { val c = it.u(); if (c in 0x20..0x7E) c.toChar() else '.' }.joinToString("")
        return if (s.count { it != '.' } >= 4) "  \"$s\"" else ""
    }

    fun guessFromSak(atqa: ByteArray, sak: Int): String = when (sak) {
        0x00 -> "MIFARE Ultralight / NTAG"
        0x08, 0x88 -> "MIFARE Classic 1K"
        0x09 -> "MIFARE Mini"
        0x18 -> "MIFARE Classic 4K"
        0x10, 0x11 -> "MIFARE Plus 2K/4K (SL2)"
        0x20 -> if (atqa.size == 2 && atqa[0].u() == 0x44 && atqa[1].u() == 0x03) "MIFARE DESFire" else "ISO 14443-4 card (bank card, phone, DESFire, passport…)"
        0x28, 0x38 -> "Smart card with MIFARE Classic emulation (e.g. JCOP)"
        0x60 -> "Phone / HCE emulated card"
        else -> "NFC-A card (SAK 0x%02X)".format(sak)
    }

    private val icMakers = mapOf(0x01 to "Motorola", 0x02 to "STMicroelectronics", 0x03 to "Hitachi", 0x04 to "NXP Semiconductors", 0x05 to "Infineon",
        0x06 to "Cylink", 0x07 to "Texas Instruments", 0x08 to "Fujitsu", 0x09 to "Matsushita", 0x0A to "NEC", 0x0B to "Oki", 0x0C to "Toshiba", 0x0D to "Mitsubishi",
        0x0E to "Samsung", 0x0F to "Hynix", 0x10 to "LG", 0x16 to "EM Microelectronic", 0x1F to "Melexis", 0x2B to "Maxim", 0x33 to "AMIC", 0x44 to "Gentag",
        0x57 to "Shanghai Fudan", 0x7A to "Identiv")

    private fun tnfName(r: NdefRecord) = when (r.tnf) {
        NdefRecord.TNF_EMPTY -> "Empty"; NdefRecord.TNF_WELL_KNOWN -> "Well-known ${String(r.type)}"; NdefRecord.TNF_MIME_MEDIA -> "MIME ${String(r.type)}"
        NdefRecord.TNF_ABSOLUTE_URI -> "Absolute URI"; NdefRecord.TNF_EXTERNAL_TYPE -> "External ${String(r.type)}"; NdefRecord.TNF_UNKNOWN -> "Unknown"
        else -> "TNF ${r.tnf}"
    }

    private fun describeRecord(r: NdefRecord): String = runCatching {
        val p = r.payload
        when {
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_URI) -> "URI: ${r.toUri()}"
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_TEXT) -> {
                val utf16 = p[0].u() and 0x80 != 0; val ll = p[0].u() and 0x3F
                "Text (${String(p, 1, ll, Charsets.US_ASCII)}): " + String(p, 1 + ll, p.size - 1 - ll, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
            }
            r.tnf == NdefRecord.TNF_WELL_KNOWN && r.type.contentEquals(NdefRecord.RTD_SMART_POSTER) -> "Smart poster: " +
                (runCatching { NdefMessage(p).records.joinToString(" | ") { describeRecord(it) } }.getOrDefault(hex(p)))
            r.tnf == NdefRecord.TNF_ABSOLUTE_URI -> "URI: ${String(p)}"
            r.tnf == NdefRecord.TNF_EXTERNAL_TYPE && String(r.type) == "android.com:pkg" -> "Android app record: ${String(p)}"
            r.tnf == NdefRecord.TNF_MIME_MEDIA && String(r.type).startsWith("text/") -> String(p)
            r.tnf == NdefRecord.TNF_MIME_MEDIA && String(r.type) == "application/vnd.wfa.wsc" -> "Wi-Fi credentials (WSC) – ${p.size} bytes, not shown"
            else -> "${p.size} bytes: " + hex(p.copyOf(minOf(p.size, 64))) + if (p.size > 64) " …" else ""
        }
    }.getOrDefault("unreadable")
}
