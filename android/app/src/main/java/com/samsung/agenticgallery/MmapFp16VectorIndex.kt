package com.samsung.agenticgallery

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.util.PriorityQueue
import java.util.zip.CRC32

class MmapFp16VectorIndex(
    rootDirectory: File,
    private val dimension: Int,
    private val compactAfterMutations: Int = 256,
) : VectorIndex {
    private sealed interface Source {
        data class Snapshot(val row: Int) : Source
        data class Delta(val vector: FloatArray) : Source
    }

    data class Status(
        val size: Int,
        val generation: Long,
        val pendingMutations: Int,
        val needsRebuild: Boolean,
        val recoveredWalTail: Boolean,
    )

    private data class SnapshotInfo(val file: File, val generation: Long, val vectorOffset: Long)

    private val root = rootDirectory.canonicalFile
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Source>()
    private val pointerFile = File(root, POINTER_FILE)
    private val walFile = File(root, WAL_FILE)
    private val rebuildMarker = File(root, REBUILD_MARKER)
    private var snapshot: SnapshotInfo? = null
    private var generation = 0L
    private var pendingMutations = 0
    private var needsRebuild = false
    private var recoveredWalTail = false

    init {
        require(dimension > 0)
        require(compactAfterMutations > 0)
        require(root.exists() || root.mkdirs()) { "Could not create vector index directory" }
        load()
    }

    override suspend fun upsert(mediaId: String, vector: FloatArray) = mutex.withLock {
        requireValidId(mediaId)
        val normalized = normalizeVector(vector, dimension)
        appendWal(OP_UPSERT, mediaId, normalized)
        entries[mediaId] = Source.Delta(normalized)
        pendingMutations += 1
        compactIfNeeded()
    }

    override suspend fun delete(mediaId: String) = mutex.withLock {
        if (entries.containsKey(mediaId)) {
            appendWal(OP_DELETE, mediaId, null)
            entries.remove(mediaId)
            pendingMutations += 1
            compactIfNeeded()
        }
        Unit
    }

    override suspend fun search(query: FloatArray, topK: Int, allowedIds: Set<String>?): List<VectorHit> = mutex.withLock {
        require(topK in 1..ReferenceVectorIndex.MAX_TOP_K)
        val normalizedQuery = normalizeVector(query, dimension)
        val snapshotInfo = snapshot
        val channel = snapshotInfo?.file?.takeIf(File::isFile)?.let { FileInputStream(it).channel }
        val mapped = channel?.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()).order(ByteOrder.LITTLE_ENDIAN) }
        val worstFirst = Comparator<VectorHit> { left, right ->
            val score = left.score.compareTo(right.score)
            if (score != 0) score else right.mediaId.compareTo(left.mediaId)
        }
        val best = PriorityQueue(topK, worstFirst)
        fun offer(hit: VectorHit) {
            if (best.size < topK) {
                best += hit
            } else if (ReferenceVectorIndex.HIT_ORDER.compare(hit, best.peek()) < 0) {
                best.poll()
                best += hit
            }
        }
        val snapshotEntries = entries.asSequence()
            .filter { (id, source) -> source is Source.Snapshot && (allowedIds == null || id in allowedIds) }
            .map { (id, source) -> id to (source as Source.Snapshot).row }
            .toList()
        val nativeScores = if (NativeVectorScanner.isAvailable && mapped != null && snapshotInfo != null && snapshotEntries.isNotEmpty()) {
            NativeVectorScanner.dotFp16Matrix(
                mapped,
                snapshotInfo.vectorOffset,
                dimension,
                snapshotEntries.mapToIntArray { it.second },
                normalizedQuery,
            )
        } else null
        if (nativeScores != null && nativeScores.size == snapshotEntries.size) {
            snapshotEntries.forEachIndexed { index, (id) -> offer(VectorHit(id, nativeScores[index])) }
        } else {
            snapshotEntries.forEach { (id, row) ->
                offer(VectorHit(id, dotSnapshot(requireNotNull(mapped), requireNotNull(snapshotInfo), row, normalizedQuery)))
            }
        }
        entries.forEach { (id, source) ->
            if (source is Source.Delta && (allowedIds == null || id in allowedIds)) {
                offer(VectorHit(id, dotProduct(source.vector, normalizedQuery)))
            }
        }
        best.sortedWith(ReferenceVectorIndex.HIT_ORDER)
    }

    suspend fun replaceAll(vectors: Map<String, FloatArray>) = mutex.withLock {
        val normalized = linkedMapOf<String, Source>()
        vectors.forEach { (id, vector) ->
            requireValidId(id)
            normalized[id] = Source.Delta(normalizeVector(vector, dimension))
        }
        entries.clear()
        entries.putAll(normalized)
        pendingMutations = maxOf(1, entries.size)
        compactLocked()
    }

    suspend fun forceCompact() = mutex.withLock { compactLocked() }

    suspend fun status(): Status = mutex.withLock {
        Status(entries.size, generation, pendingMutations, needsRebuild, recoveredWalTail)
    }

    suspend fun ids(): Set<String> = mutex.withLock { entries.keys.toSet() }

    suspend fun vector(id: String): FloatArray? = mutex.withLock {
        when (val source = entries[id] ?: return@withLock null) {
            is Source.Delta -> source.vector.copyOf()
            is Source.Snapshot -> {
                val snapshotInfo = snapshot ?: return@withLock null
                RandomAccessFile(snapshotInfo.file, "r").use { input ->
                    input.seek(snapshotInfo.vectorOffset + source.row.toLong() * dimension * 2L)
                    FloatArray(dimension) {
                        val low = input.readUnsignedByte()
                        val high = input.readUnsignedByte()
                        Fp16.toFloat(((high shl 8) or low).toShort())
                    }
                }
            }
        }
    }

    val backendName: String get() = if (NativeVectorScanner.isAvailable) "native-fp16" else "kotlin-fp16"

    internal suspend fun activeSnapshotFileForTest(): File? = mutex.withLock { snapshot?.file }

    private fun load() {
        needsRebuild = rebuildMarker.isFile
        if (!pointerFile.exists()) {
            loadWal()
            return
        }
        runCatching {
            val name = pointerFile.readText().trim()
            require(name.matches(SNAPSHOT_NAME)) { "Invalid vector snapshot pointer" }
            val active = File(root, name).canonicalFile
            require(active.parentFile == root && active.isFile) { "Vector snapshot is missing" }
            val loaded = validateSnapshot(active)
            snapshot = loaded
            generation = loaded.generation
            loadWal()
        }.onFailure { recoverCorruption() }
    }

    private fun validateSnapshot(file: File, applyEntries: Boolean = true): SnapshotInfo {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() >= HEADER_BYTES + SHA_BYTES)
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Invalid vector snapshot magic" }
            require(input.readInt() == FORMAT_VERSION)
            require(input.readInt() == dimension) { "Vector dimension changed" }
            val count = input.readInt()
            require(count in 0..MAX_VECTOR_COUNT)
            val fileGeneration = input.readLong()
            val idTableBytes = input.readInt()
            val vectorOffset = input.readLong()
            require(idTableBytes >= 0 && vectorOffset == HEADER_BYTES.toLong() + idTableBytes)
            val expectedLength = vectorOffset + count.toLong() * dimension * 2L + SHA_BYTES
            require(expectedLength == input.length() && expectedLength <= Int.MAX_VALUE)
            val ids = ArrayList<String>(count)
            repeat(count) {
                val length = input.readInt()
                require(length in 1..ReferenceVectorIndex.MAX_ID_BYTES)
                ids += ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
            }
            require(input.filePointer == vectorOffset)
            require(ids.toSet().size == ids.size) { "Duplicate media IDs in vector snapshot" }
            require(snapshotDigest(file, input.length() - SHA_BYTES).contentEquals(readTrailingDigest(input))) {
                "Vector snapshot checksum mismatch"
            }
            if (applyEntries) {
                entries.clear()
                ids.forEachIndexed { row, id -> entries[id] = Source.Snapshot(row) }
            }
            return SnapshotInfo(file, fileGeneration, vectorOffset)
        }
    }

    private fun readTrailingDigest(input: RandomAccessFile): ByteArray {
        input.seek(input.length() - SHA_BYTES)
        return ByteArray(SHA_BYTES).also(input::readFully)
    }

    private fun snapshotDigest(file: File, length: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(count > 0)
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        return digest.digest()
    }

    private fun loadWal() {
        if (!walFile.isFile) return
        RandomAccessFile(walFile, "rw").use { wal ->
            var validLength = 0L
            while (wal.filePointer < wal.length()) {
                val recordStart = wal.filePointer
                val payloadLength = try {
                    wal.readInt()
                } catch (_: EOFException) {
                    break
                }
                if (payloadLength !in 5..maxWalPayload() || wal.length() - wal.filePointer < payloadLength + 4L) break
                val payload = ByteArray(payloadLength).also(wal::readFully)
                val expectedCrc = wal.readInt()
                val actualCrc = CRC32().apply { update(payload) }.value.toInt()
                if (expectedCrc != actualCrc || !applyWalPayload(payload)) break
                validLength = wal.filePointer
                if (validLength <= recordStart) break
            }
            if (validLength != wal.length()) {
                wal.setLength(validLength)
                wal.fd.sync()
                recoveredWalTail = true
            }
        }
    }

    private fun applyWalPayload(payload: ByteArray): Boolean = runCatching {
        DataInputStream(payload.inputStream()).use { input ->
            val operation = input.readByte()
            val idLength = input.readInt()
            require(idLength in 1..ReferenceVectorIndex.MAX_ID_BYTES)
            val id = ByteArray(idLength).also(input::readFully).toString(Charsets.UTF_8)
            when (operation) {
                OP_UPSERT -> {
                    require(input.available() == dimension * 2)
                    val vector = FloatArray(dimension) { Fp16.toFloat(input.readShort()) }
                    entries[id] = Source.Delta(vector)
                }
                OP_DELETE -> {
                    require(input.available() == 0)
                    entries.remove(id)
                }
                else -> error("Unknown vector journal operation")
            }
            require(input.available() == 0)
        }
        pendingMutations += 1
        true
    }.getOrDefault(false)

    private fun appendWal(operation: Byte, mediaId: String, vector: FloatArray?) {
        val idBytes = mediaId.toByteArray(Charsets.UTF_8)
        val payload = java.io.ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(operation.toInt())
                output.writeInt(idBytes.size)
                output.write(idBytes)
                vector?.forEach { output.writeShort(Fp16.fromFloat(it).toInt()) }
            }
        }.toByteArray()
        val crc = CRC32().apply { update(payload) }.value.toInt()
        RandomAccessFile(walFile, "rw").use { wal ->
            wal.seek(wal.length())
            wal.writeInt(payload.size)
            wal.write(payload)
            wal.writeInt(crc)
            wal.fd.sync()
        }
    }

    private fun compactIfNeeded() {
        if (pendingMutations >= compactAfterMutations) compactLocked()
    }

    private fun compactLocked() {
        if (pendingMutations == 0 && snapshot != null) return
        val nextGeneration = generation + 1
        val next = File(root, "vectors-$nextGeneration.snapshot")
        val temp = File(root, "vectors-$nextGeneration.snapshot.next")
        if (temp.exists()) require(temp.delete())
        val currentSnapshot = snapshot
        val oldVectors = currentSnapshot?.let { RandomAccessFile(it.file, "r") }
        try {
            val ids = entries.keys.toList()
            val idTableBytes = ids.sumOf { 4 + it.toByteArray(Charsets.UTF_8).size }
            val vectorOffset = HEADER_BYTES.toLong() + idTableBytes
            val fileOutput = FileOutputStream(temp)
            val digest = MessageDigest.getInstance("SHA-256")
            val digestOutput = DigestOutputStream(BufferedOutputStream(fileOutput), digest)
            val output = DataOutputStream(digestOutput)
            output.write(MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeInt(dimension)
            output.writeInt(ids.size)
            output.writeLong(nextGeneration)
            output.writeInt(idTableBytes)
            output.writeLong(vectorOffset)
            ids.forEach { id ->
                val bytes = id.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
            entries.values.forEach { source ->
                when (source) {
                    is Source.Delta -> source.vector.forEach { value ->
                        val bits = Fp16.fromFloat(value).toInt() and 0xffff
                        output.writeByte(bits and 0xff)
                        output.writeByte(bits ushr 8)
                    }
                    is Source.Snapshot -> {
                        val input = requireNotNull(oldVectors)
                        input.seek(requireNotNull(currentSnapshot).vectorOffset + source.row.toLong() * dimension * 2L)
                        repeat(dimension * 2) { output.writeByte(input.readUnsignedByte()) }
                    }
                }
            }
            output.flush()
            digestOutput.on(false)
            output.write(digest.digest())
            output.flush()
            fileOutput.fd.sync()
            output.close()
        } finally {
            oldVectors?.close()
        }
        moveAtomically(temp, next)
        val validated = validateSnapshot(next, applyEntries = false)
        writePointer(next.name)
        truncateWal()
        snapshot = validated
        generation = nextGeneration
        entries.keys.toList().forEachIndexed { row, id -> entries[id] = Source.Snapshot(row) }
        pendingMutations = 0
        needsRebuild = false
        if (rebuildMarker.exists()) require(rebuildMarker.delete())
        cleanupOldSnapshots(next)
    }

    private fun writePointer(name: String) {
        val temp = File(root, "$POINTER_FILE.next")
        FileOutputStream(temp).use { output ->
            output.write(name.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        moveAtomically(temp, pointerFile)
    }

    private fun truncateWal() {
        RandomAccessFile(walFile, "rw").use {
            it.setLength(0)
            it.fd.sync()
        }
    }

    private fun moveAtomically(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cleanupOldSnapshots(active: File) {
        root.listFiles { file -> SNAPSHOT_NAME.matches(file.name) }
            ?.filter { it != active }
            ?.sortedByDescending(File::lastModified)
            ?.drop(1)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun recoverCorruption() {
        needsRebuild = true
        entries.clear()
        snapshot = null
        generation = 0
        pendingMutations = 0
        val suffix = ".corrupt-${System.currentTimeMillis()}"
        pointerFile.takeIf(File::isFile)?.readText()?.trim()?.takeIf(SNAPSHOT_NAME::matches)?.let { name ->
            val active = File(root, name)
            if (active.isFile) runCatching { active.renameTo(File(root, active.name + suffix)) }
        }
        pointerFile.takeIf(File::exists)?.let { runCatching { it.renameTo(File(root, it.name + suffix)) } }
        walFile.takeIf(File::exists)?.let { runCatching { it.renameTo(File(root, it.name + suffix)) } }
        rebuildMarker.writeText("reindex_required")
    }

    private fun dotSnapshot(buffer: ByteBuffer, info: SnapshotInfo, row: Int, query: FloatArray): Float {
        var score = 0.0
        var offset = info.vectorOffset + row.toLong() * dimension * 2L
        repeat(dimension) { index ->
            score += Fp16.toFloat(buffer.getShort(offset.toInt())).toDouble() * query[index].toDouble()
            offset += 2
        }
        return score.toFloat()
    }

    private fun requireValidId(mediaId: String) {
        require(mediaId.isNotBlank() && mediaId.toByteArray(Charsets.UTF_8).size <= ReferenceVectorIndex.MAX_ID_BYTES)
    }

    private fun maxWalPayload(): Int = 1 + 4 + ReferenceVectorIndex.MAX_ID_BYTES + dimension * 2

    private companion object {
        val MAGIC = "AGVEC001".toByteArray(Charsets.US_ASCII)
        val SNAPSHOT_NAME = Regex("vectors-[1-9][0-9]*\\.snapshot")
        const val FORMAT_VERSION = 2
        const val HEADER_BYTES = 40
        const val SHA_BYTES = 32
        const val MAX_VECTOR_COUNT = 1_000_000
        const val POINTER_FILE = "active.ptr"
        const val WAL_FILE = "vectors.wal"
        const val REBUILD_MARKER = "needs-rebuild.marker"
        const val OP_UPSERT: Byte = 1
        const val OP_DELETE: Byte = 2
    }
}

private inline fun <T> Iterable<T>.mapToIntArray(transform: (T) -> Int): IntArray {
    val values = this as? Collection<T>
    val result = IntArray(values?.size ?: count())
    var index = 0
    for (item in this) result[index++] = transform(item)
    return result
}
