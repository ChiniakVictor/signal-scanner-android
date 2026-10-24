package com.victor.signalscanner

import org.junit.Assert.*
import org.junit.Test

class ParseTest {
    private fun h(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun channels() {
        assertEquals(1, WifiParse.channelOf(2412)); assertEquals(14, WifiParse.channelOf(2484)); assertEquals(149, WifiParse.channelOf(5745))
        assertEquals(37, WifiParse.channelOf(6135)); assertEquals("6 GHz", WifiParse.band(6135))
        assertEquals("Wi-Fi 6E (802.11ax)", WifiParse.standardName(6, 6135)); assertEquals("Wi-Fi 7 (802.11be)", WifiParse.standardName(8, 5180))
    }

    @Test fun wifiIes() {
        val rsn = h("0100 000FAC04 0100 000FAC04 0200 000FAC02 000FAC08 C000")   // PSK + SAE, PMF required+capable
        val bssLoad = h("0700 80 0000")
        val country = h("555320 01 0B 1E 24 08 17")
        val htCap = h("EF09 1B FFFF0000 00000000000000000000 0000 00000000 00")
        val vhtCap = h("B2018033 FAFF 0000 FAFF 0000")
        val heCap = h("0108000000 00 0C00000000000000000000 FAFF FAFF") // mac(6) phy(11) mcs
        val heOp = h("F43F00 2A FCFF")
        val wps = h("0050F204 104A000110 1044000102 10210004 41636D65 10230004 4D6F6465")
        val items = listOf(Triple(0, -1, "MyNet".toByteArray()), Triple(48, -1, rsn), Triple(11, -1, bssLoad), Triple(7, -1, country), Triple(45, -1, htCap),
            Triple(191, -1, vhtCap), Triple(255, 35, h("010800000000") + h("0C000000000000000000 00") + h("FAFF FAFF")), Triple(255, 36, heOp), Triple(221, -1, wps),
            Triple(127, -1, h("0400080000000040")))
        val ie = WifiParse.parseIes(items)
        assertEquals(listOf("PSK", "SAE"), ie.rsn!!.akms)
        assertTrue(ie.rsn!!.mfpRequired)
        assertEquals("WPA2/WPA3-Personal (transition)", WifiParse.security("[RSN-PSK+SAE-CCMP][ESS]", ie))
        assertEquals(7, ie.stationCount); assertEquals(50, ie.channelUtil); assertEquals("US", ie.country)
        assertEquals(2, ie.htStreams); assertEquals(2, ie.vhtStreams); assertEquals(2, ie.heStreams)
        assertEquals(42, ie.bssColor)
        assertTrue(ie.wps); assertEquals("Acme", ie.wpsDevice["Manufacturer"]); assertEquals("configured", ie.wpsState)
        assertTrue(ie.v11)
        assertEquals(1201.0, WifiParse.maxRate(6, 80, 2)!!, 0.1)
        ie.list.forEach { println("${it.label} ${it.name}: ${it.summary}") }
    }

    @Test fun security() {
        assertEquals("Open (no encryption)", WifiParse.security("[ESS]", null))
        assertEquals("WPA3-Personal (SAE)", WifiParse.security("[RSN-SAE-CCMP][ESS][MFPR][MFPC]", null))
        assertEquals("Enhanced Open (OWE)", WifiParse.security("[RSN-OWE-CCMP][ESS]", null))
        assertEquals("WPA2-Enterprise (802.1X)", WifiParse.security("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]", null))
    }

    @Test fun ble() {
        // iBeacon
        val ib = BtParse.decodeManufacturer(0x004C, h("0215 E2C56DB5DFFB48D2B060D0F5A71096E0 0001 0002 C5"))!!
        assertEquals("iBeacon", ib.title); assertTrue(ib.lines[0].contains("e2c56db5-dffb-48d2-b060-d0f5a71096e0")); assertTrue(ib.lines[2].contains("-59"))
        // AirPods Pro proximity pairing
        val ap = BtParse.decodeManufacturer(0x004C, h("0719 01 0E20 2B 99 8F 01 00 05"))!!
        assertEquals("AirPods / Beats", ap.title); assertTrue(ap.lines[0].contains("AirPods Pro"))
        // Find My separated (len 25)
        val fm = BtParse.decodeManufacturer(0x004C, h("1219 10" + "00".repeat(24)))!!
        assertTrue(fm.title.startsWith("Find My (AirTag"))
        // real capture: Nearby Info + overflow area, and encrypted proximity pairing
        val ni = BtParse.decodeManufacturer(0x004C, h("01 00 00 00 00 00 00 00 00 00 00 00 00 80 00 00 00"))!!
        assertEquals(listOf("Overflow area (iOS app advertising in the background)"), ni.lines)
        val enc = BtParse.decodeManufacturer(0x004C, h("07 11 06 1B 87 57 83 A4 CA B8 18 35 89 3F 89 F9 15 27 E5 07 16 B2 FC 01 01 6E 01"))!!
        assertTrue(enc.lines[0].contains("encrypted"))
        // Microsoft CDP
        val ms = BtParse.decodeManufacturer(0x0006, h("0109 2002 1122334455667788"))!!
        assertTrue(ms.lines[0].contains("Windows 10/11 desktop"))
        // Eddystone URL https://www.google.com/
        val ed = BtParse.decodeService(0xFEAA, h("10 EB 01") + "google".toByteArray() + h("00"))!!
        assertEquals("URL https://www.google.com/", ed.lines[0])
        // Fast Pair model id
        assertTrue(BtParse.decodeService(0xFE2C, h("2C8A0D"))!!.lines[0].contains("Model ID 0x2C8A0D"))
        // AD structures
        val f = BtParse.fields(h("020106 03030FFE 09094D79205461672021 020AF4"))
        assertEquals(listOf(1, 3, 9, 10), f.map { it.type })
        assertEquals("-12 dBm", f[3].value)
        assertEquals("Random static", BtParse.addressKind("C1:22:33:44:55:66", 1))
        assertEquals("Public (fixed, from the maker)", BtParse.addressKind("00:22:33:44:55:66", 0))
        assertEquals("Phone · Smartphone (services: networking, audio, telephony) [0x62020C]", BtParse.classOfDevice(0x62020C))
        assertEquals(1.0, BtParse.distanceM(-59, -59), 0.01)
    }
}
