package com.nuvio.app.features.cloudstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CloudStreamPackageInspectorTest {
    @Test
    fun inspectsSafeCs3CentralDirectory() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val inspection = CloudStreamPackageInspector.inspect(archive)
        assertTrue(inspection.containsManifest)
        assertTrue(inspection.containsDex)
        assertEquals(2, inspection.entries.size)
    }

    @Test
    fun rejectsPathTraversal() {
        val archive = fakeZip("manifest.json", "classes.dex", "../escape.txt")
        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsCentralDirectoryThatOverlapsEndRecord() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val endOffset = archive.size - 22
        val declaredDirectorySize = archive.readIntLeForTest(endOffset + 12)
        archive.writeIntLeForTest(endOffset + 12, declaredDirectorySize + 1)

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsEntryThatRunsPastDeclaredCentralDirectory() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val endOffset = archive.size - 22
        archive.writeIntLeForTest(endOffset + 12, 1)

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsMultiDiskArchives() {
        val archive = fakeZip("manifest.json", "classes.dex")
        val endOffset = archive.size - 22
        archive[endOffset + 4] = 1

        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(archive)
        }
    }

    @Test
    fun rejectsPackageWithoutDex() {
        assertFailsWith<IllegalArgumentException> {
            CloudStreamPackageInspector.inspect(fakeZip("manifest.json"))
        }
    }

    private fun fakeZip(vararg names: String): ByteArray {
        val centralEntries = names.map { name ->
            byteArrayOf(0x50, 0x4b, 0x01, 0x02) +
                ByteArray(16) +
                intLe(1) +
                intLe(1) +
                shortLe(name.encodeToByteArray().size) +
                shortLe(0) +
                shortLe(0) +
                ByteArray(12) +
                name.encodeToByteArray()
        }
        val central = centralEntries.fold(ByteArray(0)) { acc, entry -> acc + entry }
        val end = byteArrayOf(0x50, 0x4b, 0x05, 0x06) +
            shortLe(0) + shortLe(0) +
            shortLe(names.size) + shortLe(names.size) +
            intLe(central.size) + intLe(0) + shortLe(0)
        return central + end
    }

    private fun ByteArray.readIntLeForTest(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArray.writeIntLeForTest(offset: Int, value: Int) {
        repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun shortLe(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )
}

