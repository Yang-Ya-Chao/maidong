package com.local.ktv

import org.junit.Assert.*
import org.junit.Test

class UsbDocumentPolicyTest {
    @Test fun acceptsSystemVolumeAndVendorOpaqueDocumentIds() {
        assertTrue(UsbDocumentPolicy.acceptsFolder("com.android.externalstorage.documents", "1234-ABCD:歌曲"))
        assertTrue(UsbDocumentPolicy.acceptsFolder("com.vendor.usb.documents", "ntfs/drive-one/歌曲"))
        assertTrue(UsbDocumentPolicy.acceptsFolder("com.plugin.exfat.documents", "opaque-root"))
    }

    @Test fun rejectsInternalSystemVolumeAndEmptySelection() {
        for (id in listOf("primary:歌曲", "home:", "downloads:")) {
            assertFalse(UsbDocumentPolicy.acceptsFolder("com.android.externalstorage.documents", id))
        }
        assertFalse(UsbDocumentPolicy.acceptsFolder(null, "songs"))
        assertFalse(UsbDocumentPolicy.acceptsFolder("com.vendor.usb.documents", ""))
    }

    private fun mkvHeader(size: Int = 64): ByteArray = ByteArray(size).apply {
        byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()).copyInto(this)
    }

    @Test fun unknownFileSizeUsesActualHeaderInsteadOfRejectingReadableMkv() {
        assertTrue(UsbDocumentPolicy.validHeader(mkvHeader(), -1))
        assertTrue(UsbDocumentPolicy.validHeader(mkvHeader(), 2048))
    }

    @Test fun rejectsTruncatedMediaEvenWhenSizeIsUnknown() {
        assertFalse(UsbDocumentPolicy.validHeader(mkvHeader(63), -1))
        assertFalse(UsbDocumentPolicy.validHeader(mkvHeader(), 63))
    }

    @Test fun rejectsNonMediaAndAcceptsMp4Header() {
        assertFalse(UsbDocumentPolicy.validHeader(ByteArray(64), -1))
        val mp4 = ByteArray(64).apply { "ftyp".toByteArray().copyInto(this, 4) }
        assertTrue(UsbDocumentPolicy.validHeader(mp4, -1))
    }
}
