package com.nuvio.app.features.cloudstream

data class CloudStreamPackageEntry(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
)

data class CloudStreamPackageInspection(
    val entries: List<CloudStreamPackageEntry>,
) {
    val containsManifest: Boolean
        get() = entries.any { it.name == "manifest.json" }

    val containsDex: Boolean
        get() = entries.any { it.name == "classes.dex" }
}

object CloudStreamPackageInspector {
    private const val centralDirectorySignature = 0x02014b50
    private const val endOfCentralDirectorySignature = 0x06054b50
    private const val maxEntries = 256
    private const val maxTotalUncompressedBytes = 50L * 1024L * 1024L

    fun inspect(bytes: ByteArray): CloudStreamPackageInspection {
        require(bytes.size >= 22) { "Invalid .cs3 ZIP archive" }
        val endOffset = findEndOfCentralDirectory(bytes)
        val diskNumber = bytes.readUInt16Le(endOffset + 4)
        val centralDirectoryDisk = bytes.readUInt16Le(endOffset + 6)
        val entriesOnDisk = bytes.readUInt16Le(endOffset + 8)
        val entryCount = bytes.readUInt16Le(endOffset + 10)
        val centralDirectorySize = bytes.readUInt32Le(endOffset + 12)
        val centralDirectoryOffset = bytes.readUInt32Le(endOffset + 16)
        require(diskNumber == 0 && centralDirectoryDisk == 0 && entriesOnDisk == entryCount) {
            "Multi-disk .cs3 archives are not supported"
        }
        require(entryCount in 1..maxEntries) { "Invalid .cs3 entry count" }
        // The directory must end before the EOCD record. Checking only against the
        // archive size would allow malformed entries to consume bytes from the EOCD
        // record or its comment as if they were central-directory records.
        val centralDirectoryEnd = centralDirectoryOffset + centralDirectorySize
        require(
            centralDirectoryOffset <= endOffset.toLong() &&
                centralDirectoryEnd <= endOffset.toLong() &&
                centralDirectoryEnd <= bytes.size.toLong()
        ) {
            "Invalid .cs3 central directory"
        }

        val entries = ArrayList<CloudStreamPackageEntry>(entryCount)
        val entryNames = mutableSetOf<String>()
        var cursor = centralDirectoryOffset.toInt()
        var totalUncompressed = 0L
        repeat(entryCount) {
            require(cursor.toLong() + 46L <= centralDirectoryEnd) {
                "Truncated .cs3 central directory entry"
            }
            require(bytes.readInt32Le(cursor) == centralDirectorySignature) { "Invalid .cs3 central directory entry" }
            val compressedSize = bytes.readUInt32Le(cursor + 20)
            val uncompressedSize = bytes.readUInt32Le(cursor + 24)
            val diskNumberStart = bytes.readUInt16Le(cursor + 34)
            require(diskNumberStart == 0) { "Multi-disk .cs3 entries are not supported" }
            val nameLength = bytes.readUInt16Le(cursor + 28)
            val extraLength = bytes.readUInt16Le(cursor + 30)
            val commentLength = bytes.readUInt16Le(cursor + 32)
            val nameStart = cursor + 46
            val nameEndLong = nameStart.toLong() + nameLength
            val entryEndLong = nameEndLong + extraLength + commentLength
            require(
                nameEndLong <= centralDirectoryEnd &&
                    entryEndLong <= centralDirectoryEnd &&
                    entryEndLong <= bytes.size.toLong()
            ) {
                "Invalid .cs3 central directory entry length"
            }
            val nameEnd = nameEndLong.toInt()
            val name = bytes.copyOfRange(nameStart, nameEnd).decodeToString()
            validateEntryName(name)
            require(entryNames.add(name)) { ".cs3 archive contains duplicate entry paths" }
            totalUncompressed += uncompressedSize
            require(totalUncompressed <= maxTotalUncompressedBytes) { ".cs3 archive is too large when extracted" }
            entries += CloudStreamPackageEntry(
                name = name,
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
            )
            cursor = entryEndLong.toInt()
        }

        return CloudStreamPackageInspection(entries).also { inspection ->
            require(inspection.containsManifest) { ".cs3 package is missing manifest.json" }
            require(inspection.containsDex) { ".cs3 package is missing classes.dex" }
        }
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        val minimum = (bytes.size - 65_557).coerceAtLeast(0)
        for (offset in bytes.size - 22 downTo minimum) {
            if (bytes.readInt32Le(offset) != endOfCentralDirectorySignature) continue
            val commentLength = bytes.readUInt16Le(offset + 20)
            // A ZIP end record (including its optional comment) must terminate the
            // archive. Allowing trailing bytes can make a signature inside a comment
            // or an appended payload look like a second valid end record.
            if (offset.toLong() + 22L + commentLength == bytes.size.toLong()) return offset
        }
        error("Invalid .cs3 ZIP end record")
    }

    private fun validateEntryName(name: String) {
        require(name.isNotBlank()) { ".cs3 contains a blank entry path" }
        require(!name.startsWith('/') && !name.startsWith('\\')) { ".cs3 contains an absolute entry path" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(name)) { ".cs3 contains a drive-qualified entry path" }
        require(name.split('/', '\\').none { it == ".." }) { ".cs3 contains path traversal" }
    }
}

private fun ByteArray.readUInt16Le(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size) { "Unexpected end of ZIP data" }
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.readInt32Le(offset: Int): Int {
    require(offset >= 0 && offset + 4 <= size) { "Unexpected end of ZIP data" }
    return (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)
}

private fun ByteArray.readUInt32Le(offset: Int): Long = readInt32Le(offset).toLong() and 0xffffffffL

