package io.github.anup42.askalbum

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipFile

enum class GemmaModelTier { E2B, E4B }

data class GemmaArtifactSpec(val role: String, val name: String, val sizeBytes: Long, val sha256: String)

data class GemmaPackManifest(
    val schemaVersion: Int,
    val packId: String,
    val packVersion: String,
    val family: String,
    val tier: GemmaModelTier,
    val multimodal: Boolean,
    val maxContextTokens: Int,
    val sourceRevision: String,
    val sourceLicense: String,
    val runtimeName: String,
    val runtimeVersion: String,
    val minimumRamBytes: Long,
    val signatureAlgorithm: String,
    val signingKeySha256: String,
    val files: List<GemmaArtifactSpec>,
) {
    fun file(role: String): GemmaArtifactSpec = files.single { it.role == role }

    companion object {
        private val safeId = Regex("[A-Za-z0-9._-]{1,96}")
        private val revision = Regex("[0-9a-f]{40,64}")
        private val digest = Regex("[0-9a-f]{64}")

        fun parse(bytes: ByteArray): GemmaPackManifest {
            require(bytes.isNotEmpty() && bytes.size <= MAX_GEMMA_MANIFEST_BYTES) { "Invalid Gemma manifest size" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8)).requireExactKeys(
                "schemaVersion", "packId", "packVersion", "model", "source", "runtime", "device", "signing", "files",
            )
            val model = json.getJSONObject("model").requireExactKeys("family", "tier", "multimodal", "maxContextTokens")
            val source = json.getJSONObject("source").requireExactKeys("revision", "license")
            val runtime = json.getJSONObject("runtime").requireExactKeys("name", "version")
            val device = json.getJSONObject("device").requireExactKeys("minimumRamBytes")
            val signing = json.getJSONObject("signing").requireExactKeys("algorithm", "keySha256")
            val fileArray = json.getJSONArray("files")
            require(fileArray.length() == 2) { "Gemma pack must contain exactly model and license artifacts" }
            val files = List(fileArray.length()) { index ->
                val item = fileArray.getJSONObject(index).requireExactKeys("role", "name", "sizeBytes", "sha256")
                GemmaArtifactSpec(
                    role = item.getString("role"),
                    name = item.getString("name"),
                    sizeBytes = item.getLong("sizeBytes"),
                    sha256 = item.getString("sha256").lowercase(),
                )
            }
            return GemmaPackManifest(
                schemaVersion = json.getInt("schemaVersion"),
                packId = json.getString("packId"),
                packVersion = json.getString("packVersion"),
                family = model.getString("family"),
                tier = GemmaModelTier.valueOf(model.getString("tier")),
                multimodal = model.getBoolean("multimodal"),
                maxContextTokens = model.getInt("maxContextTokens"),
                sourceRevision = source.getString("revision").lowercase(),
                sourceLicense = source.getString("license"),
                runtimeName = runtime.getString("name"),
                runtimeVersion = runtime.getString("version"),
                minimumRamBytes = device.getLong("minimumRamBytes"),
                signatureAlgorithm = signing.getString("algorithm"),
                signingKeySha256 = signing.getString("keySha256").lowercase(),
                files = files,
            ).also { manifest ->
                require(manifest.schemaVersion == 1) { "Unsupported Gemma-pack schema" }
                require(safeId.matches(manifest.packId) && safeId.matches(manifest.packVersion)) { "Invalid Gemma pack identifier" }
                require(manifest.family == "gemma-4") { "Unsupported generative model family" }
                require(manifest.multimodal) { "Gemma pack must support image verification" }
                require(manifest.maxContextTokens in 1024..32000) { "Unsupported Gemma context size" }
                require(revision.matches(manifest.sourceRevision)) { "Gemma source revision must be pinned" }
                require(manifest.sourceLicense.isNotBlank() && manifest.sourceLicense.length <= 96) { "Invalid Gemma license identifier" }
                require(manifest.runtimeName == "LiteRT-LM" && manifest.runtimeVersion == LITERT_LM_VERSION) { "Unsupported LiteRT-LM runtime" }
                require(manifest.minimumRamBytes in 2L * GIB..32L * GIB) { "Invalid minimum RAM declaration" }
                require(manifest.signatureAlgorithm in setOf("SHA256withRSA", "SHA256withECDSA")) { "Unsupported Gemma signature algorithm" }
                require(digest.matches(manifest.signingKeySha256)) { "Invalid Gemma signing-key fingerprint" }
                require(manifest.files.map { it.role }.toSet() == setOf(GEMMA_ROLE_MODEL, GEMMA_ROLE_LICENSE)) { "Invalid Gemma artifact roles" }
                require(manifest.files.map { it.name }.distinct().size == manifest.files.size) { "Duplicate Gemma artifact filename" }
                manifest.files.forEach { spec ->
                    require(safeId.matches(spec.name)) { "Unsafe Gemma artifact filename" }
                    require(spec.sizeBytes in 1..MAX_GEMMA_ARTIFACT_BYTES) { "Invalid Gemma artifact size" }
                    require(digest.matches(spec.sha256)) { "Invalid Gemma artifact checksum" }
                }
                require(manifest.file(GEMMA_ROLE_MODEL).name.endsWith(".litertlm")) { "Gemma model artifact must be .litertlm" }
                require(manifest.file(GEMMA_ROLE_MODEL).sizeBytes >= MIN_GEMMA_MODEL_BYTES) { "Gemma model artifact is too small" }
                require(manifest.file(GEMMA_ROLE_LICENSE).sizeBytes <= MAX_GEMMA_LICENSE_BYTES) { "Gemma license file is too large" }
                require(manifest.files.sumOf { it.sizeBytes } <= MAX_GEMMA_PACK_BYTES) { "Gemma pack is too large" }
                if (manifest.tier == GemmaModelTier.E4B) {
                    require(manifest.minimumRamBytes >= 8L * GIB) { "E4B pack understates its RAM requirement" }
                }
            }
        }
    }
}

data class GemmaDeviceAssessment(
    val supported: Boolean,
    val recommendedTier: GemmaModelTier,
    val totalRamBytes: Long,
    val memoryClassMb: Int,
    val reason: String,
)

class GemmaDeviceCapability(private val context: Context) {
    fun assess(manifest: GemmaPackManifest? = null): GemmaDeviceAssessment {
        return assess(manifest?.tier, manifest?.minimumRamBytes)
    }

    fun assess(spec: GemmaDownloadSpec): GemmaDeviceAssessment = assess(spec.tier, spec.minimumRamBytes)

    private fun assess(tier: GemmaModelTier?, declaredMinimumRamBytes: Long?): GemmaDeviceAssessment {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val arm64 = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }
        val recommended = if (arm64 && memory.totalMem >= 10L * GIB && manager.memoryClass >= 512) GemmaModelTier.E4B else GemmaModelTier.E2B
        val required = declaredMinimumRamBytes ?: 4L * GIB
        val policyFloor = when (tier) {
            GemmaModelTier.E4B -> 10L * GIB
            GemmaModelTier.E2B -> 4L * GIB
            null -> 4L * GIB
        }
        val supported = arm64 && memory.totalMem >= maxOf(required, policyFloor)
        val reason = when {
            !arm64 -> "A 64-bit ARM device is required"
            memory.totalMem < maxOf(required, policyFloor) -> "The model pack requires more physical RAM"
            tier == GemmaModelTier.E4B && recommended != GemmaModelTier.E4B -> "E4B requires a 12 GB-class device; E2B is recommended"
            else -> "Compatible with bounded on-device inference"
        }
        return GemmaDeviceAssessment(supported, recommended, memory.totalMem, manager.memoryClass, reason)
    }
}

data class ModelPackStatus(
    val installed: Boolean,
    val name: String = "No verified Gemma pack",
    val path: String? = null,
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val runtimeVersion: String = "LiteRT-LM $LITERT_LM_VERSION",
    val packId: String? = null,
    val packVersion: String? = null,
    val tier: GemmaModelTier? = null,
    val selectedTier: GemmaModelTier = GemmaModelTier.E2B,
    val installedTiers: Set<GemmaModelTier> = emptySet(),
    val downloadAllowed: Boolean = BuildConfig.ALLOW_MODEL_DOWNLOAD,
    val multimodal: Boolean = false,
    val deviceAssessment: GemmaDeviceAssessment? = null,
    val error: String? = null,
)

data class InstalledGemmaPack(val directory: File, val manifest: GemmaPackManifest) {
    fun artifact(role: String): File = File(directory, manifest.file(role).name)
}

class GemmaPackSignatureVerifier(private val publicKey: PublicKey) {
    fun verify(manifestBytes: ByteArray, signatureBytes: ByteArray, manifest: GemmaPackManifest) {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        require(MessageDigest.isEqual(fingerprint, manifest.signingKeySha256.gemmaHexToBytes())) {
            "Gemma pack was not signed by this app's signing key"
        }
        val algorithm = when (publicKey.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> error("Unsupported APK signing-key algorithm")
        }
        require(manifest.signatureAlgorithm == algorithm) { "Gemma signature algorithm does not match the APK signing key" }
        val verifier = Signature.getInstance(algorithm)
        verifier.initVerify(publicKey)
        verifier.update(manifestBytes)
        require(verifier.verify(signatureBytes)) { "Invalid Gemma-pack signature" }
    }
}

class ModelPackManager(
    private val context: Context,
    private val signatureVerifier: GemmaPackSignatureVerifier = GemmaPackSignatureVerifier(context.gemmaApkSigningPublicKey()),
    private val deviceCapability: GemmaDeviceCapability = GemmaDeviceCapability(context),
) {
    private val root = File(context.filesDir, "models/gemma")
    private val generations = File(root, "generations")
    private val pointer = File(root, "current")
    private val previousPointer = File(root, "previous")
    private val preferences = context.getSharedPreferences("gemma-model-selection", Context.MODE_PRIVATE)

    fun selectedTier(): GemmaModelTier = runCatching {
        GemmaModelTier.valueOf(preferences.getString("tier", GemmaModelTier.E2B.name)!!)
    }.getOrDefault(GemmaModelTier.E2B)

    fun assess(spec: GemmaDownloadSpec): GemmaDeviceAssessment = deviceCapability.assess(spec)

    fun isInstalled(tier: GemmaModelTier): Boolean = findInstalled(tier) != null

    fun selectTier(tier: GemmaModelTier): ModelPackStatus {
        preferences.edit().putString("tier", tier.name).apply()
        findInstalled(tier)?.let { activateGeneration(it.directory.name) }
        return status()
    }

    fun status(): ModelPackStatus = runCatching {
        val preferred = selectedTier()
        val tiers = installedTiers()
        val installed = current()
            ?: return ModelPackStatus(
                installed = false,
                selectedTier = preferred,
                installedTiers = tiers,
                deviceAssessment = deviceCapability.assess(GemmaModelCatalog.require(preferred)),
            )
        val model = installed.artifact(GEMMA_ROLE_MODEL)
        ModelPackStatus(
            installed = true,
            name = "Gemma 4 ${installed.manifest.tier} verified pack",
            path = model.absolutePath,
            sizeBytes = model.length(),
            sha256 = installed.manifest.file(GEMMA_ROLE_MODEL).sha256,
            packId = installed.manifest.packId,
            packVersion = installed.manifest.packVersion,
            tier = installed.manifest.tier,
            selectedTier = installed.manifest.tier,
            installedTiers = tiers,
            multimodal = installed.manifest.multimodal,
            deviceAssessment = deviceCapability.assess(installed.manifest),
        )
    }.getOrElse {
        ModelPackStatus(
            installed = false,
            selectedTier = selectedTier(),
            installedTiers = installedTiers(),
            deviceAssessment = deviceCapability.assess(GemmaModelCatalog.require(selectedTier())),
            error = it.message,
        )
    }

    fun current(): InstalledGemmaPack? {
        if (!pointer.isFile) return null
        val name = pointer.readText().trim()
        require(name.matches(Regex("generation-[A-Za-z0-9._-]+"))) { "Invalid Gemma generation pointer" }
        val directory = File(generations, name)
        require(directory.isDirectory && directory.canonicalPath.startsWith(generations.canonicalPath + File.separator)) { "Gemma generation is unavailable" }
        val manifest = GemmaPackManifest.parse(File(directory, GEMMA_MANIFEST_NAME).readBytes())
        manifest.files.forEach { spec ->
            val artifact = File(directory, spec.name)
            require(artifact.isFile && artifact.length() == spec.sizeBytes) { "Gemma artifact is incomplete" }
        }
        return InstalledGemmaPack(directory, manifest)
    }

    suspend fun import(uri: Uri): ModelPackStatus = withContext(Dispatchers.IO) {
        root.mkdirs()
        generations.mkdirs()
        val declared = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (declared >= 0) {
            require(declared <= MAX_GEMMA_ARCHIVE_BYTES) { "Gemma pack archive is too large" }
            require(StatFs(root.absolutePath).availableBytes > declared + MIN_FREE_AFTER_GEMMA_IMPORT) { "Not enough space to stage the Gemma pack" }
        }
        val incoming = File(root, "incoming-${UUID.randomUUID()}.zip")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected Gemma pack cannot be opened" }
                FileOutputStream(incoming).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_GEMMA_ARCHIVE_BYTES) { "Gemma pack archive is too large" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val installed = installVerified(incoming)
            preferences.edit().putString("tier", installed.manifest.tier.name).apply()
            status()
        } finally {
            incoming.delete()
        }
    }

    internal fun installVerified(zipFile: File): InstalledGemmaPack {
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries().toList()
            require(entries.size == 4 && entries.map { it.name }.distinct().size == entries.size) { "Gemma pack must have four unique entries" }
            val manifestEntry = zip.getEntry(GEMMA_MANIFEST_NAME) ?: error("Gemma pack has no manifest")
            val signatureEntry = zip.getEntry(GEMMA_SIGNATURE_NAME) ?: error("Gemma pack has no signature")
            require(manifestEntry.size in 1..MAX_GEMMA_MANIFEST_BYTES.toLong()) { "Invalid Gemma manifest size" }
            require(signatureEntry.size in 1..MAX_GEMMA_SIGNATURE_BYTES.toLong()) { "Invalid Gemma signature size" }
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.gemmaReadBytesLimited(MAX_GEMMA_MANIFEST_BYTES) }
            val manifest = GemmaPackManifest.parse(manifestBytes)
            val signature = zip.getInputStream(signatureEntry).use {
                Base64.getDecoder().decode(it.gemmaReadBytesLimited(MAX_GEMMA_SIGNATURE_BYTES).toString(Charsets.US_ASCII).trim())
            }
            signatureVerifier.verify(manifestBytes, signature, manifest)
            val assessment = deviceCapability.assess(manifest)
            require(assessment.supported) { assessment.reason }
            if (manifest.tier == GemmaModelTier.E4B) require(assessment.recommendedTier == GemmaModelTier.E4B) { assessment.reason }
            val allowed = manifest.files.mapTo(mutableSetOf()) { it.name }.apply { add(GEMMA_MANIFEST_NAME); add(GEMMA_SIGNATURE_NAME) }
            require(entries.all { !it.isDirectory && it.name in allowed } && entries.map { it.name }.toSet() == allowed) { "Gemma pack contains an unlisted or missing entry" }
            val required = manifest.files.sumOf { it.sizeBytes }
            require(StatFs(root.absolutePath).availableBytes > required + MIN_FREE_AFTER_GEMMA_IMPORT) { "Not enough app-private storage for the Gemma pack" }

            val generationName = "generation-${manifest.packVersion}-${UUID.randomUUID()}"
            val staging = File(generations, "$generationName.importing")
            val installed = File(generations, generationName)
            require(staging.mkdirs()) { "Could not create Gemma staging directory" }
            try {
                manifest.files.forEach { spec ->
                    val entry = zip.getEntry(spec.name) ?: error("Missing Gemma ${spec.role} artifact")
                    require(entry.size == spec.sizeBytes) { "Declared Gemma size differs for ${spec.name}" }
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
                                require(written <= spec.sizeBytes) { "Expanded Gemma artifact exceeds its declaration" }
                                digest.update(buffer, 0, count)
                                target.write(buffer, 0, count)
                            }
                            target.fd.sync()
                        }
                    }
                    require(written == spec.sizeBytes && digest.digest().gemmaToHex() == spec.sha256) { "Checksum mismatch for ${spec.name}" }
                }
                File(staging, GEMMA_MANIFEST_NAME).gemmaWriteBytesAndSync(manifestBytes)
                File(staging, GEMMA_SIGNATURE_NAME).gemmaWriteBytesAndSync(Base64.getEncoder().encode(signature))
                require(staging.renameTo(installed)) { "Could not finalize Gemma generation" }
                pointer.takeIf(File::isFile)?.readText()?.trim()?.takeIf { it.matches(Regex("generation-[A-Za-z0-9._-]+")) }?.let {
                    previousPointer.gemmaWriteTextAndSync(it)
                }
                val next = File(root, "current.next")
                next.gemmaWriteTextAndSync(generationName)
                if (pointer.exists()) require(pointer.delete()) { "Could not replace Gemma generation pointer" }
                require(next.renameTo(pointer)) { "Could not activate Gemma generation" }
                return InstalledGemmaPack(installed, manifest)
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
    }

    internal fun installDownloaded(spec: GemmaDownloadSpec, downloadedFile: File): InstalledGemmaPack {
        require(BuildConfig.ALLOW_MODEL_DOWNLOAD) { "Network model downloads are disabled in this build" }
        require(downloadedFile.isFile && downloadedFile.length() == spec.sizeBytes) { "Downloaded model is incomplete" }
        val digest = MessageDigest.getInstance("SHA-256")
        downloadedFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().gemmaToHex() == spec.sha256) { "Downloaded model checksum does not match the pinned catalog" }
        val assessment = deviceCapability.assess(spec)
        require(assessment.supported) { assessment.reason }
        if (spec.tier == GemmaModelTier.E4B) require(assessment.recommendedTier == GemmaModelTier.E4B) { assessment.reason }

        val licenseBytes = GEMMA_TERMS_NOTICE.toByteArray(Charsets.UTF_8)
        val licenseName = "GEMMA_TERMS_NOTICE.txt"
        val publicKey = context.gemmaApkSigningPublicKey()
        val signatureAlgorithm = when (publicKey.algorithm.uppercase()) {
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> error("Unsupported APK signing-key algorithm")
        }
        val keySha = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).gemmaToHex()
        val licenseSha = MessageDigest.getInstance("SHA-256").digest(licenseBytes).gemmaToHex()
        val manifestJson = JSONObject()
            .put("schemaVersion", 1)
            .put("packId", "google-ai-edge-gemma-4-${spec.tier.name.lowercase()}")
            .put("packVersion", spec.revision.take(16))
            .put("model", JSONObject().put("family", "gemma-4").put("tier", spec.tier.name).put("multimodal", true).put("maxContextTokens", 32000))
            .put("source", JSONObject().put("revision", spec.revision).put("license", "Gemma Terms"))
            .put("runtime", JSONObject().put("name", "LiteRT-LM").put("version", LITERT_LM_VERSION))
            .put("device", JSONObject().put("minimumRamBytes", spec.minimumRamBytes))
            .put("signing", JSONObject().put("algorithm", signatureAlgorithm).put("keySha256", keySha))
            .put(
                "files",
                org.json.JSONArray()
                    .put(JSONObject().put("role", GEMMA_ROLE_MODEL).put("name", spec.fileName).put("sizeBytes", spec.sizeBytes).put("sha256", spec.sha256))
                    .put(JSONObject().put("role", GEMMA_ROLE_LICENSE).put("name", licenseName).put("sizeBytes", licenseBytes.size).put("sha256", licenseSha)),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        val manifest = GemmaPackManifest.parse(manifestJson)
        generations.mkdirs()
        val generationName = "generation-${manifest.packVersion}-${UUID.randomUUID()}"
        val staging = File(generations, "$generationName.importing")
        val installed = File(generations, generationName)
        require(staging.mkdirs()) { "Could not create Gemma download staging directory" }
        try {
            require(downloadedFile.renameTo(File(staging, spec.fileName))) { "Could not move the verified model into app-private storage" }
            File(staging, licenseName).gemmaWriteBytesAndSync(licenseBytes)
            File(staging, GEMMA_MANIFEST_NAME).gemmaWriteBytesAndSync(manifestJson)
            require(staging.renameTo(installed)) { "Could not finalize the downloaded Gemma generation" }
            activateGeneration(generationName)
            preferences.edit().putString("tier", spec.tier.name).apply()
            return InstalledGemmaPack(installed, manifest)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private fun installedTiers(): Set<GemmaModelTier> = GemmaModelTier.entries.filterTo(mutableSetOf(), ::isInstalled)

    private fun findInstalled(tier: GemmaModelTier): InstalledGemmaPack? {
        return generations.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name.matches(Regex("generation-[A-Za-z0-9._-]+")) }
            .sortedByDescending(File::lastModified)
            .mapNotNull { directory ->
                runCatching {
                    val manifest = GemmaPackManifest.parse(File(directory, GEMMA_MANIFEST_NAME).readBytes())
                    require(manifest.tier == tier)
                    manifest.files.forEach { spec -> require(File(directory, spec.name).let { it.isFile && it.length() == spec.sizeBytes }) }
                    InstalledGemmaPack(directory, manifest)
                }.getOrNull()
            }
            .firstOrNull()
    }

    private fun activateGeneration(generationName: String) {
        require(generationName.matches(Regex("generation-[A-Za-z0-9._-]+")) && File(generations, generationName).isDirectory)
        val active = pointer.takeIf(File::isFile)?.readText()?.trim()
        if (active == generationName) return
        active?.takeIf { it.matches(Regex("generation-[A-Za-z0-9._-]+")) }?.let(previousPointer::gemmaWriteTextAndSync)
        val next = File(root, "current.next")
        next.gemmaWriteTextAndSync(generationName)
        if (pointer.exists()) require(pointer.delete()) { "Could not replace Gemma generation pointer" }
        require(next.renameTo(pointer)) { "Could not activate Gemma generation" }
    }

    fun rollbackAfterLoadFailure(activeModelPath: String): Boolean = runCatching {
        val active = current() ?: return false
        if (active.artifact(GEMMA_ROLE_MODEL).absolutePath != activeModelPath || !previousPointer.isFile) return false
        val previous = previousPointer.readText().trim()
        require(previous.matches(Regex("generation-[A-Za-z0-9._-]+")) && File(generations, previous).isDirectory)
        val failed = pointer.readText().trim()
        pointer.gemmaWriteTextAndSync(previous)
        previousPointer.gemmaWriteTextAndSync(failed)
        true
    }.getOrDefault(false)
}

private fun JSONObject.requireExactKeys(vararg allowed: String): JSONObject {
    val keys = keys().asSequence().toSet()
    require(keys == allowed.toSet()) { "Manifest contains unknown or missing fields" }
    return this
}

private fun Context.gemmaApkSigningPublicKey(): PublicKey {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
    val info = packageManager.getPackageInfo(packageName, flags)
    val bytes = if (Build.VERSION.SDK_INT >= 28) {
        requireNotNull(requireNotNull(info.signingInfo).apkContentsSigners).single().toByteArray()
    } else {
        @Suppress("DEPRECATION") requireNotNull(info.signatures).single().toByteArray()
    }
    return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()).publicKey
}

private fun java.io.InputStream.gemmaReadBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= limit) { "Gemma control file is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun File.gemmaWriteBytesAndSync(bytes: ByteArray) = FileOutputStream(this).use { it.write(bytes); it.fd.sync() }
private fun File.gemmaWriteTextAndSync(text: String) = gemmaWriteBytesAndSync(text.toByteArray(Charsets.UTF_8))
private fun ByteArray.gemmaToHex(): String = joinToString("") { "%02x".format(it) }
private fun String.gemmaHexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

const val LITERT_LM_VERSION = "0.14.0"
const val GEMMA_ROLE_MODEL = "model"
const val GEMMA_ROLE_LICENSE = "license"
private const val GEMMA_MANIFEST_NAME = "manifest.json"
private const val GEMMA_SIGNATURE_NAME = "manifest.sig"
private const val MAX_GEMMA_MANIFEST_BYTES = 256 * 1024
private const val MAX_GEMMA_SIGNATURE_BYTES = 16 * 1024
private const val MAX_GEMMA_LICENSE_BYTES = 2L * 1024 * 1024
private const val MIN_GEMMA_MODEL_BYTES = 50L * 1024 * 1024
private const val MAX_GEMMA_ARTIFACT_BYTES = 7_500_000_000L
private const val MAX_GEMMA_PACK_BYTES = 7_600_000_000L
private const val MAX_GEMMA_ARCHIVE_BYTES = 7_700_000_000L
private const val MIN_FREE_AFTER_GEMMA_IMPORT = 512L * 1024 * 1024
private const val GIB = 1024L * 1024 * 1024
private const val GEMMA_TERMS_NOTICE = "Gemma model use is governed by the Gemma Terms. Review and accept the current terms at https://ai.google.dev/gemma/terms before downloading or using this model."
