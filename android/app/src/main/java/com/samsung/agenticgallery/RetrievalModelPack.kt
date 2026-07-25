package com.samsung.agenticgallery

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipFile

data class RetrievalPackFile(
    val role: String,
    val name: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class RetrievalPackManifest(
    val schemaVersion: Int,
    val packId: String,
    val packVersion: String,
    val sourceModel: String,
    val sourceRevision: String,
    val sourceLicense: String,
    val artifactRepository: String?,
    val artifactRevision: String?,
    val runtime: String,
    val runtimeVersion: String,
    val embeddingDimension: Int,
    val normalized: Boolean,
    val minimumSimilarity: Float,
    val imageSize: Int,
    val imageLayout: String,
    val resizeMethod: String,
    val imageMean: FloatArray,
    val imageStd: FloatArray,
    val textLength: Int,
    val lowercaseText: Boolean,
    val padTokenId: Int,
    val eosTokenId: Int,
    val textInputType: String,
    val signatureAlgorithm: String,
    val signingKeySha256: String,
    val files: List<RetrievalPackFile>,
) {
    fun file(role: String): RetrievalPackFile =
        files.singleOrNull { it.role == role } ?: error("Missing exactly one $role artifact")

    companion object {
        private val safeId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val sha256 = Regex("[0-9a-f]{64}")
        private val supportedRoles = setOf(ROLE_IMAGE_ENCODER, ROLE_TEXT_ENCODER, ROLE_TOKENIZER, ROLE_LICENSE)

        fun parse(bytes: ByteArray): RetrievalPackManifest {
            require(bytes.size <= MAX_MANIFEST_BYTES) { "Manifest is too large" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val source = json.getJSONObject("source")
            val artifact = json.optJSONObject("artifact")
            val runtime = json.getJSONObject("runtime")
            val embedding = json.getJSONObject("embedding")
            val image = json.getJSONObject("image")
            val text = json.getJSONObject("text")
            val signing = json.getJSONObject("signing")
            val filesJson = json.getJSONArray("files")
            require(filesJson.length() in 4..MAX_PACK_FILES) { "Unexpected model-pack file count" }
            val files = buildList {
                repeat(filesJson.length()) { index ->
                    val item = filesJson.getJSONObject(index)
                    add(
                        RetrievalPackFile(
                            role = item.getString("role"),
                            name = item.getString("name"),
                            sizeBytes = item.getLong("sizeBytes"),
                            sha256 = item.getString("sha256").lowercase(),
                        )
                    )
                }
            }
            val manifest = RetrievalPackManifest(
                schemaVersion = json.getInt("schemaVersion"),
                packId = json.getString("packId"),
                packVersion = json.getString("packVersion"),
                sourceModel = source.getString("model"),
                sourceRevision = source.getString("revision"),
                sourceLicense = source.getString("license"),
                artifactRepository = artifact?.getString("repository"),
                artifactRevision = artifact?.getString("revision"),
                runtime = runtime.getString("name"),
                runtimeVersion = runtime.getString("version"),
                embeddingDimension = embedding.getInt("dimension"),
                normalized = embedding.getBoolean("normalized"),
                minimumSimilarity = embedding.getDouble("minimumSimilarity").toFloat(),
                imageSize = image.getInt("size"),
                imageLayout = image.getString("layout"),
                resizeMethod = image.getString("resize"),
                imageMean = image.getJSONArray("mean").toFloatArray(),
                imageStd = image.getJSONArray("std").toFloatArray(),
                textLength = text.getInt("length"),
                lowercaseText = text.getBoolean("lowercase"),
                padTokenId = text.getInt("padTokenId"),
                eosTokenId = text.getInt("eosTokenId"),
                textInputType = text.getString("inputType"),
                signatureAlgorithm = signing.getString("algorithm"),
                signingKeySha256 = signing.getString("keySha256").lowercase(),
                files = files,
            )
            manifest.validate()
            return manifest
        }

        private fun org.json.JSONArray.toFloatArray(): FloatArray =
            FloatArray(length()) { index -> getDouble(index).toFloat() }

        private fun RetrievalPackManifest.validate() {
            require(schemaVersion == 1) { "Unsupported retrieval-pack schema" }
            require(safeId.matches(packId) && safeId.matches(packVersion)) { "Invalid pack identifier" }
            require(sourceModel == "google/siglip2-base-patch16-224") { "Unsupported source model" }
            require(sourceRevision.matches(Regex("[0-9a-f]{40}"))) { "Source revision must be pinned" }
            require(sourceLicense == "apache-2.0") { "Unsupported source-model license" }
            val isLiteRt = runtime == RETRIEVAL_RUNTIME_LITERT && runtimeVersion == LITERT_VERSION
            val isOnnx = runtime == RETRIEVAL_RUNTIME_ONNX && runtimeVersion == ONNX_RUNTIME_VERSION
            require(isLiteRt || isOnnx) { "Unsupported retrieval runtime" }
            if (isOnnx) {
                require(artifactRepository == ONNX_SIGLIP2_REPOSITORY) { "Unsupported ONNX artifact repository" }
                require(artifactRevision?.matches(Regex("[0-9a-f]{40}")) == true) { "ONNX artifact revision must be pinned" }
            } else {
                require(artifactRepository == null && artifactRevision == null) { "LiteRT packs must not declare an ONNX artifact" }
            }
            require(embeddingDimension in 128..2048 && normalized) { "Invalid embedding contract" }
            require(minimumSimilarity in -1f..1f) { "Invalid semantic similarity threshold" }
            require(imageSize in 128..512 && imageLayout in setOf("NCHW", "NHWC") && resizeMethod == "BICUBIC") {
                "Unsupported image preprocessing"
            }
            require(imageMean.size == 3 && imageStd.size == 3 && imageStd.all { it > 0f }) { "Invalid image normalization" }
            require(textLength in 8..256 && textInputType in setOf("INT32", "INT64")) { "Unsupported text contract" }
            require(signatureAlgorithm in setOf("SHA256withRSA", "SHA256withECDSA")) { "Unsupported signature algorithm" }
            require(sha256.matches(signingKeySha256)) { "Invalid signing-key fingerprint" }
            require(files.map { it.name }.distinct().size == files.size) { "Duplicate model-pack filename" }
            require(files.map { it.role }.distinct().size == files.size) { "Duplicate model-pack role" }
            files.forEach {
                require(it.role in supportedRoles) { "Unsupported model-pack role" }
                require(safeId.matches(it.name)) { "Unsafe model-pack filename" }
                require(it.sizeBytes in 1..MAX_ARTIFACT_BYTES) { "Invalid model-pack artifact size" }
                require(sha256.matches(it.sha256)) { "Invalid artifact checksum" }
            }
            val encoderSuffix = if (isOnnx) ".onnx" else ".tflite"
            require(file(ROLE_IMAGE_ENCODER).name.endsWith(encoderSuffix)) { "Image encoder has the wrong runtime format" }
            require(file(ROLE_TEXT_ENCODER).name.endsWith(encoderSuffix)) { "Text encoder has the wrong runtime format" }
            file(ROLE_TOKENIZER)
            file(ROLE_LICENSE)
            require(files.sumOf { it.sizeBytes } <= MAX_PACK_BYTES) { "Model pack is too large" }
        }
    }
}

data class RetrievalPackStatus(
    val installed: Boolean,
    val packId: String? = null,
    val packVersion: String? = null,
    val sourceRevision: String? = null,
    val embeddingDimension: Int? = null,
    val installedBytes: Long = 0,
    val error: String? = null,
)

class RetrievalPackSignatureVerifier(private val publicKey: PublicKey) {
    fun verify(manifestBytes: ByteArray, signatureBytes: ByteArray, manifest: RetrievalPackManifest) {
        val fingerprint = sha256(publicKey.encoded)
        require(MessageDigest.isEqual(fingerprint, manifest.signingKeySha256.hexToBytes())) {
            "Model pack was not signed by this app's signing key"
        }
        val expectedAlgorithm = when (publicKey.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> error("Unsupported APK signing-key algorithm")
        }
        require(manifest.signatureAlgorithm == expectedAlgorithm) { "Signature algorithm does not match the APK signing key" }
        val verifier = Signature.getInstance(expectedAlgorithm)
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        require(verifier.verify(signatureBytes)) { "Invalid retrieval-pack signature" }
    }
}

class RetrievalModelPackManager(
    private val context: Context,
    private val verifier: RetrievalPackSignatureVerifier = RetrievalPackSignatureVerifier(context.apkSigningPublicKey()),
) {
    private val root = File(context.filesDir, "models/retrieval")
    private val generations = File(root, "generations")
    private val pointer = File(root, "current")

    fun status(): RetrievalPackStatus = runCatching {
        val installed = current() ?: return RetrievalPackStatus(installed = false)
        RetrievalPackStatus(
            installed = true,
            packId = installed.manifest.packId,
            packVersion = installed.manifest.packVersion,
            sourceRevision = installed.manifest.sourceRevision,
            embeddingDimension = installed.manifest.embeddingDimension,
            installedBytes = installed.manifest.files.sumOf { it.sizeBytes },
        )
    }.getOrElse { RetrievalPackStatus(installed = false, error = it.message) }

    fun current(): InstalledRetrievalPack? {
        if (!pointer.isFile) return null
        val generationName = pointer.readText().trim()
        require(generationName.matches(Regex("generation-[A-Za-z0-9._-]+"))) { "Invalid retrieval generation pointer" }
        val directory = File(generations, generationName)
        require(directory.isDirectory && directory.canonicalPath.startsWith(generations.canonicalPath + File.separator)) {
            "Retrieval generation is unavailable"
        }
        val manifestBytes = File(directory, MANIFEST_NAME).readBytes()
        val manifest = RetrievalPackManifest.parse(manifestBytes)
        manifest.files.forEach { spec ->
            val artifact = File(directory, spec.name)
            require(artifact.isFile && artifact.length() == spec.sizeBytes) { "Retrieval artifact is incomplete" }
        }
        return InstalledRetrievalPack(directory, manifest)
    }

    suspend fun import(uri: Uri): RetrievalPackStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        root.mkdirs()
        generations.mkdirs()
        val incoming = File(root, "incoming-${UUID.randomUUID()}.zip")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected retrieval pack cannot be opened" }
                FileOutputStream(incoming).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_PACK_BYTES) { "Retrieval pack is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            installVerified(incoming)
            status()
        } finally {
            incoming.delete()
        }
    }

    internal fun installVerified(zipFile: File): InstalledRetrievalPack {
        return installArchive(zipFile, requireApkSignature = true, embeddedSpec = null)
    }

    internal fun installEmbedded(
        spec: EmbeddedRetrievalSpec,
        zipFile: File,
    ): InstalledRetrievalPack {
        require(zipFile.length() == spec.archiveSizeBytes) { "Embedded retrieval pack has the wrong size" }
        require(zipFile.sha256Hex() == spec.archiveSha256) { "Embedded retrieval pack failed SHA-256 verification" }
        return installArchive(zipFile, requireApkSignature = false, embeddedSpec = spec)
    }

    private fun installArchive(
        zipFile: File,
        requireApkSignature: Boolean,
        embeddedSpec: EmbeddedRetrievalSpec?,
    ): InstalledRetrievalPack {
        require(root.mkdirs() || root.isDirectory) { "Could not create retrieval model directory" }
        require(generations.mkdirs() || generations.isDirectory) { "Could not create retrieval generation directory" }
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size <= MAX_PACK_FILES + 2) { "Retrieval pack has too many entries" }
            require(entries.map { it.name }.distinct().size == entries.size) { "Retrieval pack has duplicate entries" }
            val manifestEntry = zip.getEntry(MANIFEST_NAME) ?: error("Retrieval pack has no manifest")
            val signatureEntry = zip.getEntry(SIGNATURE_NAME) ?: error("Retrieval pack has no signature")
            require(manifestEntry.size in 1..MAX_MANIFEST_BYTES.toLong()) { "Invalid retrieval manifest size" }
            require(signatureEntry.size in 1..MAX_SIGNATURE_BYTES.toLong()) { "Invalid retrieval signature size" }
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytesLimited(MAX_MANIFEST_BYTES) }
            val manifest = RetrievalPackManifest.parse(manifestBytes)
            val signatureBytes = zip.getInputStream(signatureEntry).use { input ->
                Base64.getDecoder().decode(input.readBytesLimited(MAX_SIGNATURE_BYTES).toString(Charsets.US_ASCII).trim())
            }
            if (requireApkSignature) verifier.verify(manifestBytes, signatureBytes, manifest)
            embeddedSpec?.validate(manifest)
            val allowed = manifest.files.mapTo(mutableSetOf()) { it.name }.apply { add(MANIFEST_NAME); add(SIGNATURE_NAME) }
            require(entries.all { !it.isDirectory && it.name in allowed }) { "Retrieval pack contains an unlisted entry" }
            require(entries.map { it.name }.toSet() == allowed) { "Retrieval pack is missing an artifact" }
            val requiredBytes = manifest.files.sumOf { it.sizeBytes }
            require(StatFs(root.absolutePath).availableBytes > requiredBytes + MIN_FREE_AFTER_IMPORT) {
                "Not enough app-private storage for the retrieval pack"
            }

            val generationName = "generation-${manifest.packVersion}-${UUID.randomUUID()}"
            val staging = File(generations, "$generationName.importing")
            val installed = File(generations, generationName)
            require(staging.mkdirs()) { "Could not create retrieval-pack staging directory" }
            try {
                manifest.files.forEach { spec ->
                    val entry = zip.getEntry(spec.name) ?: error("Missing ${spec.role} artifact")
                    require(entry.size == spec.sizeBytes) { "Declared size differs for ${spec.name}" }
                    val output = File(staging, spec.name)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var written = 0L
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(output).use { target ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                written += count
                                require(written <= spec.sizeBytes) { "Expanded artifact exceeds declared size" }
                                digest.update(buffer, 0, count)
                                target.write(buffer, 0, count)
                            }
                            target.fd.sync()
                        }
                    }
                    require(written == spec.sizeBytes && digest.digest().toHex() == spec.sha256) {
                        "Checksum mismatch for ${spec.name}"
                    }
                }
                File(staging, MANIFEST_NAME).writeBytesAndSync(manifestBytes)
                File(staging, SIGNATURE_NAME).writeBytesAndSync(Base64.getEncoder().encode(signatureBytes))
                require(staging.renameTo(installed)) { "Could not finalize retrieval generation" }
                val nextPointer = File(root, "current.next")
                nextPointer.writeTextAndSync(generationName)
                if (pointer.exists()) require(pointer.delete()) { "Could not replace retrieval generation pointer" }
                require(nextPointer.renameTo(pointer)) { "Could not activate retrieval generation" }
                return InstalledRetrievalPack(installed, manifest)
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
    }
}

data class InstalledRetrievalPack(val directory: File, val manifest: RetrievalPackManifest) {
    fun artifact(role: String): File = File(directory, manifest.file(role).name)
}

private fun Context.apkSigningPublicKey(): PublicKey {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    val info = packageManager.getPackageInfo(packageName, flags)
    val certificateBytes = if (Build.VERSION.SDK_INT >= 28) {
        requireNotNull(requireNotNull(info.signingInfo).apkContentsSigners).single().toByteArray()
    } else {
        @Suppress("DEPRECATION") requireNotNull(info.signatures).single().toByteArray()
    }
    return java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(certificateBytes.inputStream()).publicKey
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Model-pack control file is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun File.writeBytesAndSync(bytes: ByteArray) = FileOutputStream(this).use { it.write(bytes); it.fd.sync() }
private fun File.writeTextAndSync(value: String) = writeBytesAndSync(value.toByteArray(Charsets.UTF_8))
private fun File.sha256Hex(): String = inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(256 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().toHex()
}
private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

const val LITERT_VERSION = "2.1.0"
const val ONNX_RUNTIME_VERSION = "1.23.2"
const val RETRIEVAL_RUNTIME_LITERT = "LiteRT"
const val RETRIEVAL_RUNTIME_ONNX = "ONNX Runtime"
const val ONNX_SIGLIP2_REPOSITORY = "onnx-community/siglip2-base-patch16-224-ONNX"
const val ROLE_IMAGE_ENCODER = "image_encoder"
const val ROLE_TEXT_ENCODER = "text_encoder"
const val ROLE_TOKENIZER = "tokenizer_vocab"
const val ROLE_LICENSE = "license"
private const val MANIFEST_NAME = "manifest.json"
private const val SIGNATURE_NAME = "manifest.sig"
private const val MAX_MANIFEST_BYTES = 256 * 1024
private const val MAX_SIGNATURE_BYTES = 16 * 1024
private const val MAX_PACK_FILES = 12
private const val MAX_ARTIFACT_BYTES = 2_000_000_000L
private const val MAX_PACK_BYTES = 3_000_000_000L
private const val MIN_FREE_AFTER_IMPORT = 512L * 1024 * 1024
