package com.seastreams.tv.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.seastreams.tv.core.webserver.LanAddressProvider
import com.seastreams.tv.core.webserver.LanAddressProvider.Candidate
import com.seastreams.tv.ui.components.encodeQrMatrix
import com.seastreams.tv.ui.navigation.AppGate
import com.seastreams.tv.ui.navigation.AppGateViewModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OnboardingGateTest {

    @Test
    fun `app stays on onboarding until a profile and a valid key both exist`() {
        assertEquals(AppGate.NeedsOnboarding, AppGateViewModel.resolveGate(profileCount = 0, hasValidKey = false))
        assertEquals(AppGate.NeedsOnboarding, AppGateViewModel.resolveGate(profileCount = 0, hasValidKey = true))
        assertEquals(AppGate.NeedsOnboarding, AppGateViewModel.resolveGate(profileCount = 2, hasValidKey = false))
        assertEquals(AppGate.Ready, AppGateViewModel.resolveGate(profileCount = 1, hasValidKey = true))
    }

    @Test
    fun `LAN address prefers private ethernet then wifi addresses`() {
        val picked = LanAddressProvider.pickBest(
            listOf(
                Candidate("rmnet0", "100.64.3.2", isSiteLocal = false),
                Candidate("wlan0", "192.168.1.40", isSiteLocal = true),
                Candidate("eth0", "10.0.0.8", isSiteLocal = true),
            ),
        )
        assertEquals("10.0.0.8", picked)
        assertNull(LanAddressProvider.pickBest(emptyList()))
        assertEquals("http://10.0.0.8:8765", LanAddressProvider.portalUrl("10.0.0.8", 8765))
    }

    @Test
    fun `QR matrix decodes back to the portal URL`() {
        val url = "http://192.168.1.40:8765"
        val matrix = encodeQrMatrix(url)
        // Render with a 4-module quiet zone at 4px/module so the reader can find the finder patterns.
        val scale = 4
        val quiet = 4
        val side = (matrix.width + quiet * 2) * scale
        val pixels = ByteArray(side * side) { i ->
            val x = (i % side) / scale - quiet
            val y = (i / side) / scale - quiet
            val dark = x in 0 until matrix.width && y in 0 until matrix.height && matrix[x, y]
            if (dark) 0 else 0xFF.toByte()
        }
        val source = object : LuminanceSource(side, side) {
            override fun getRow(y: Int, row: ByteArray?): ByteArray = pixels.copyOfRange(y * side, (y + 1) * side)
            override fun getMatrix(): ByteArray = pixels
        }

        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))

        assertEquals(url, decoded.text)
        assertEquals(BarcodeFormat.QR_CODE, decoded.barcodeFormat)
    }
}
