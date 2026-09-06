package com.stog.app.feature.plan.share_import

import android.content.ClipData
import android.content.Intent
import android.net.HostTestUri
import android.net.Uri
import android.os.Parcelable
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Field
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import sun.misc.Unsafe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareImportIntentDriverTest {
    @Test
    fun realAndroidIntentAdapterDrivesAllRequiredIntakeScenarios() {
        val root = Files.createTempDirectory("stog-share-intent-driver").toFile()
        val content = DriverContentSource().apply {
            bytes("content://driver/one", driverJpeg(1))
            bytes("content://driver/two", driverPng())
            unavailable("content://driver/unavailable")
            midCopySecurityFailure("content://driver/mid-revoked", driverJpeg(9))
            bytes("content://driver/oversized", driverJpeg(3) + ByteArray(64))
            bytes("content://driver/spoofed", "%PDFBAD".toByteArray())
        }
        val notifier = DriverNotifier()
        val store = ShareImportFileStore(
            rootDirectory = root,
            contentSource = content,
            limits = ShareImportLimits(maxAttachmentCount = 20, maxAttachmentBytes = 16),
        )
        val coordinator = ShareImportCoordinator(
            fileStore = store,
            notifier = notifier,
            executeTask = { task -> task() },
            postToMain = { task -> task() },
        )
        try {
            val textIntent = HostAndroidIntent.create(
                action = Intent.ACTION_SEND,
                mimeType = "text/html",
                text = "[네이버지도] 전주 객사 https://naver.me/driver",
                htmlText = "<p>전주 객사</p>",
                claimedSourcePackage = "spoofed.sender",
            )
            val textPayload = ShareImportReceiver.payloadFrom(textIntent)
            assertEquals("<p>전주 객사</p>", textPayload.htmlText)
            val textStates = coordinator.drive(textIntent)
            val textSaved = textStates.last() as ShareImportUiState.Saved
            val textMetadata = metadata(textSaved.local)
            println(
                "DRIVER happy-text states=${textStates.stateNames()} id=${textSaved.local.id} " +
                    "title=${textSaved.normalized.title} metadata=${metadataFields(textSaved.local)} " +
                    "metadataDigest=${textMetadata.sha256()} " +
                    "claimedPackagePersisted=${textMetadata.toString(Charsets.ISO_8859_1).contains("spoofed.sender")}",
            )

            val multiIntent = HostAndroidIntent.create(
                action = Intent.ACTION_SEND_MULTIPLE,
                mimeType = "image/*",
                streamUris = listOf("content://driver/one", "content://driver/two"),
                clipUris = listOf("content://driver/one", "content://driver/two"),
                dataUri = "content://driver/two",
            )
            val multiPayload = ShareImportReceiver.payloadFrom(multiIntent)
            assertEquals(2, multiPayload.streamUris.size)
            assertEquals(2, multiPayload.clipDataUris.size)
            assertEquals("content://driver/two", multiPayload.dataUri)
            val multiStates = coordinator.drive(multiIntent)
            val multiSaved = multiStates.last() as ShareImportUiState.Saved
            val copiedDigests = multiSaved.local.copiedAttachments.map { it.readBytes().sha256() }
            println(
                "DRIVER happy-multi-image states=${multiStates.stateNames()} " +
                    "files=${multiSaved.local.copiedAttachments.map(File::getName)} " +
                    "byteDigests=$copiedDigests metadata=${metadataFields(multiSaved.local)} " +
                    "metadataDigest=${metadata(multiSaved.local).sha256()}",
            )

            val unsupportedStates = coordinator.drive(
                HostAndroidIntent.create(
                    action = Intent.ACTION_SEND,
                    mimeType = "application/pdf",
                    text = "unsupported",
                ),
            )
            println("DRIVER unsupported-mime states=${unsupportedStates.stateNames()} message=${unsupportedStates.failureMessage()}")

            val unavailableStates = coordinator.drive(
                HostAndroidIntent.create(
                    action = Intent.ACTION_SEND,
                    mimeType = "image/jpeg",
                    streamUris = listOf("content://driver/unavailable"),
                ),
            )
            val midCopyStates = coordinator.drive(
                HostAndroidIntent.create(
                    action = Intent.ACTION_SEND,
                    mimeType = "image/jpeg",
                    streamUris = listOf("content://driver/mid-revoked"),
                ),
            )
            println(
                "DRIVER revoked-uri unavailableStates=${unavailableStates.stateNames()} " +
                    "midCopyStates=${midCopyStates.stateNames()} persistedAfterFailure=${store.loadAll().map { it.local.id }}",
            )

            val oversizedStates = coordinator.drive(
                HostAndroidIntent.create(
                    action = Intent.ACTION_SEND,
                    mimeType = "image/jpeg",
                    streamUris = listOf("content://driver/oversized"),
                ),
            )
            println("DRIVER oversized-input states=${oversizedStates.stateNames()} message=${oversizedStates.failureMessage()}")

            val spoofedStates = coordinator.drive(
                HostAndroidIntent.create(
                    action = Intent.ACTION_SEND,
                    mimeType = "image/jpeg",
                    streamUris = listOf("content://driver/spoofed"),
                    claimedSourcePackage = "com.instagram.android",
                ),
            )
            println("DRIVER spoofed-non-image states=${spoofedStates.stateNames()} message=${spoofedStates.failureMessage()}")

            println(
                "DRIVER duplicate-carriers stream=${multiPayload.streamUris} clip=${multiPayload.clipDataUris} " +
                    "data=${multiPayload.dataUri} copiedCount=${multiSaved.local.copiedAttachments.size}",
            )

            val recreatedStore = ShareImportFileStore(root, content)
            val reloaded = recreatedStore.loadAll()
            println(
                "DRIVER process-recreation count=${reloaded.size} ids=${reloaded.map { it.local.id }} " +
                    "metadataDigests=${reloaded.map { metadata(it.local).sha256() }} " +
                    "copiedByteDigests=${reloaded.flatMap { stored -> stored.local.copiedAttachments.map { it.readBytes().sha256() } }}",
            )

            val textDestination = notifier.destinations.first()
            val imageDestination = notifier.destinations.last()
            val deepLinkIntent = HostAndroidIntent.create(
                action = textDestination.action,
                mimeType = "",
                reviewImportId = textDestination.importId,
            )
            val resolvedReviewId = deepLinkIntent.shareImportReviewId()
            println(
                "DRIVER notification-destination action=${textDestination.action} " +
                    "extra=${textDestination.importIdExtra} textId=${textDestination.importId} " +
                    "imageId=${imageDestination.importId} resolvedDeepLinkId=$resolvedReviewId",
            )

            assertEquals(listOf("Saving", "Saved"), textStates.stateNames())
            assertEquals(listOf("Saving", "Saved"), multiStates.stateNames())
            listOf(unsupportedStates, unavailableStates, midCopyStates, oversizedStates, spoofedStates)
                .forEach { assertEquals(listOf("Saving", "Failed"), it.stateNames()) }
            assertEquals(2, multiSaved.local.copiedAttachments.size)
            assertEquals(listOf(driverJpeg(1).sha256(), driverPng().sha256()), copiedDigests)
            assertFalse(textMetadata.toString(Charsets.ISO_8859_1).contains("spoofed.sender"))
            assertEquals(2, reloaded.size)
            assertEquals(setOf(textSaved.local.id, multiSaved.local.id), reloaded.map { it.local.id }.toSet())
            assertEquals(2, root.listFiles().orEmpty().count { it.isDirectory })
            assertEquals(2, notifier.destinations.size)
            assertEquals(ShareImportNavigation.REVIEW_ACTION, textDestination.action)
            assertEquals(ShareImportNavigation.IMPORT_ID_EXTRA, textDestination.importIdExtra)
            assertEquals(textSaved.local.id, textDestination.importId)
            assertEquals(multiSaved.local.id, imageDestination.importId)
            assertEquals(textSaved.local.id, resolvedReviewId)
        } finally {
            coordinator.close()
            root.deleteRecursively()
        }
    }
}

private fun ShareImportCoordinator.drive(intent: Intent): List<ShareImportUiState> =
    mutableListOf<ShareImportUiState>().also { states -> start(intent, states::add) }

private fun List<ShareImportUiState>.stateNames(): List<String> = map { state ->
    when (state) {
        ShareImportUiState.Idle -> "Idle"
        is ShareImportUiState.Saving -> "Saving"
        is ShareImportUiState.Saved -> "Saved"
        is ShareImportUiState.Failed -> "Failed"
    }
}

private fun List<ShareImportUiState>.failureMessage(): String =
    (last() as ShareImportUiState.Failed).message

private fun metadata(local: LocalShareImport): ByteArray =
    File(local.directory, "metadata.properties").readBytes()

private fun metadataFields(local: LocalShareImport): Map<String, String> =
    Properties().also { properties ->
        File(local.directory, "metadata.properties").inputStream().use(properties::load)
    }.let { properties ->
        properties.stringPropertyNames().sorted().associateWith(properties::getProperty)
    }

private class DriverContentSource : ShareImportContentSource {
    private sealed interface Entry {
        data class Bytes(val value: ByteArray) : Entry
        data object Unavailable : Entry
        data class MidCopySecurity(val prefix: ByteArray) : Entry
    }

    private val entries = mutableMapOf<String, Entry>()

    fun bytes(uri: String, bytes: ByteArray) {
        entries[uri] = Entry.Bytes(bytes)
    }

    fun unavailable(uri: String) {
        entries[uri] = Entry.Unavailable
    }

    fun midCopySecurityFailure(uri: String, prefix: ByteArray) {
        entries[uri] = Entry.MidCopySecurity(prefix)
    }

    override fun mimeType(uri: String): String? = "image/jpeg"

    override fun open(uri: String): InputStream? = when (val entry = entries[uri]) {
        is Entry.Bytes -> ByteArrayInputStream(entry.value)
        Entry.Unavailable, null -> null
        is Entry.MidCopySecurity -> object : InputStream() {
            private var firstRead = true

            override fun read(): Int = error("bulk read expected")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!firstRead) throw SecurityException("provider permission revoked")
                firstRead = false
                entry.prefix.copyInto(buffer, offset)
                return entry.prefix.size
            }
        }
    }
}

private class DriverNotifier : ShareImportNotifier {
    val destinations = mutableListOf<ShareImportNotificationDestination>()

    override fun post(destination: ShareImportNotificationDestination): Boolean {
        destinations += destination
        return true
    }
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
private class HostAndroidIntent private constructor() : Intent() {
    private var actionValue: String? = null
    private var mimeTypeValue: String? = null
    private var textValue: CharSequence? = null
    private var htmlTextValue: CharSequence? = null
    private var packageValue: String? = null
    private var reviewImportIdValue: String? = null
    private var dataValue: Uri? = null
    private var streamsValue: ArrayList<Uri>? = null
    private var clipValue: ClipData? = null

    override fun getAction(): String? = actionValue
    override fun getType(): String? = mimeTypeValue
    override fun getCharSequenceExtra(name: String): CharSequence? = when (name) {
        Intent.EXTRA_TEXT -> textValue
        Intent.EXTRA_HTML_TEXT -> htmlTextValue
        else -> null
    }
    override fun getStringExtra(name: String): String? = when (name) {
        Intent.EXTRA_PACKAGE_NAME, Intent.EXTRA_REFERRER_NAME -> packageValue
        ShareImportNavigation.IMPORT_ID_EXTRA -> reviewImportIdValue
        else -> null
    }
    override fun getData(): Uri? = dataValue
    override fun getClipData(): ClipData? = clipValue

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    override fun <T : Parcelable?> getParcelableExtra(name: String): T? =
        streamsValue?.singleOrNull() as? T

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getParcelableExtra(name: String?, clazz: Class<T>): T? =
        streamsValue?.singleOrNull()?.takeIf(clazz::isInstance) as? T

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    override fun <T : Parcelable?> getParcelableArrayListExtra(name: String): ArrayList<T>? =
        streamsValue as? ArrayList<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getParcelableArrayListExtra(
        name: String?,
        clazz: Class<out T>,
    ): ArrayList<T>? = streamsValue as? ArrayList<T>

    companion object {
        fun create(
            action: String,
            mimeType: String,
            text: String? = null,
            htmlText: String? = null,
            streamUris: List<String> = emptyList(),
            clipUris: List<String> = emptyList(),
            dataUri: String? = null,
            claimedSourcePackage: String? = null,
            reviewImportId: String? = null,
        ): Intent = hostUnsafe.allocateInstance(HostAndroidIntent::class.java).let { raw ->
            (raw as HostAndroidIntent).apply {
                actionValue = action
                mimeTypeValue = mimeType
                textValue = text
                htmlTextValue = htmlText
                packageValue = claimedSourcePackage
                reviewImportIdValue = reviewImportId
                dataValue = dataUri?.let(HostTestUri::create)
                streamsValue = ArrayList(streamUris.map(HostTestUri::create))
                clipValue = clipUris.takeIf(List<String>::isNotEmpty)?.let(HostClipData::create)
            }
        }
    }
}

private class HostClipData private constructor() : ClipData("host", emptyArray(), ClipData.Item("host")) {
    private var itemsValue: List<ClipData.Item>? = null

    override fun getItemCount(): Int = itemsValue.orEmpty().size
    override fun getItemAt(index: Int): ClipData.Item = itemsValue.orEmpty()[index]

    companion object {
        fun create(uris: List<String>): ClipData =
            hostUnsafe.allocateInstance(HostClipData::class.java).let { raw ->
                (raw as HostClipData).apply {
                    itemsValue = uris.map { HostClipItem.create(HostTestUri.create(it)) }
                }
            }
    }
}

private class HostClipItem private constructor() : ClipData.Item("host") {
    private var uriValue: Uri? = null

    override fun getUri(): Uri? = uriValue
    override fun getText(): CharSequence? = null

    companion object {
        fun create(uri: Uri): ClipData.Item =
            hostUnsafe.allocateInstance(HostClipItem::class.java).let { raw ->
                (raw as HostClipItem).apply { uriValue = uri }
            }
    }
}

private val hostUnsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let { field: Field ->
    field.isAccessible = true
    field.get(null) as Unsafe
}

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

private fun driverJpeg(marker: Int): ByteArray = byteArrayOf(
    0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), marker.toByte(), 1, 2, 3,
)

private fun driverPng(): ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
)
