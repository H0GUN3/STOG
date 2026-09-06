package com.stog.app.feature.plan.share_import

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShareImportIntakeTest {
    private lateinit var root: File
    private lateinit var content: FakeShareImportContentSource
    private lateinit var notifications: RecordingShareImportNotifier

    @Before
    fun setUp() {
        root = Files.createTempDirectory("stog-share-intake-test").toFile()
        content = FakeShareImportContentSource()
        notifications = RecordingShareImportNotifier()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun singleTextIntentIsNormalizedPersistedAndNotified() {
        val result = service().intake(
            payload(
                mimeType = "text/plain",
                texts = listOf("[카카오맵] 전주 카페 https://kko.to/cafe"),
                claimedSourcePackage = "malicious.claim",
            ),
        ).accepted()

        assertEquals("전주 카페", result.stored.normalized.title)
        assertEquals(ShareSource.KAKAO, result.stored.normalized.source)
        assertEquals(ShareImportStatus.SAVED, result.stored.status)
        assertEquals(result.stored.local.id, notifications.single().importId)
    }

    @Test
    fun htmlTextIsUsedAsFallbackWithoutPersistingMarkupAsTheTitle() {
        val result = service().intake(
            payload(
                mimeType = "text/html",
                htmlText = "<p>전주 한옥 카페</p><a href=\"https://example.test/place\">보기</a>",
            ),
        ).accepted()

        assertEquals("전주 한옥 카페", result.stored.normalized.title)
        assertEquals("https://example.test/place", result.stored.normalized.originalUrl)
        assertTrue(result.stored.local.copiedAttachments.isEmpty())
    }

    @Test
    fun singleImageIntentCopiesVerifiedBytesIntoPrivateStorage() {
        content.add("content://sender/photo", jpeg(), "image/jpeg")

        val result = service().intake(
            payload(mimeType = "image/jpeg", streamUris = listOf("content://sender/photo")),
        ).accepted()

        val copied = result.stored.local.copiedAttachments.single()
        assertTrue(copied.isFile)
        assertEquals(jpeg().toList(), copied.readBytes().toList())
        assertTrue(copied.canonicalPath.startsWith(root.canonicalPath))
        assertFalse(metadataText(result.stored).contains("content://sender/photo"))
    }

    @Test
    fun multipleImageIntentCopiesEveryDistinctImage() {
        content.add("content://sender/one", jpeg(1), "image/jpeg")
        content.add("content://sender/two", png(), "image/png")

        val result = service().intake(
            payload(
                action = ShareImportActions.SEND_MULTIPLE,
                mimeType = "image/*",
                streamUris = listOf("content://sender/one", "content://sender/two"),
            ),
        ).accepted()

        assertEquals(2, result.stored.local.copiedAttachments.size)
        assertEquals(listOf("image/jpeg", "image/png"), result.stored.local.copiedAttachmentMimeTypes)
    }

    @Test
    fun clipDataAndDataUriAreNormalizedAndDuplicateCarriersAreCopiedOnce() {
        content.add("content://sender/one", jpeg(), null)
        content.add("content://sender/two", png(), null)

        val result = service().intake(
            payload(
                action = ShareImportActions.SEND_MULTIPLE,
                mimeType = "image/*",
                streamUris = listOf("content://sender/one"),
                clipDataUris = listOf("content://sender/one", "content://sender/two"),
                dataUri = "content://sender/two",
            ),
        ).accepted()

        assertEquals(2, result.stored.local.copiedAttachments.size)
    }

    @Test
    fun mixedAndUnsupportedMimeInputsAreRejectedBeforePersistence() {
        content.add("content://sender/photo", jpeg(), "image/jpeg")

        val mixed = service().intake(
            payload(
                mimeType = "text/plain",
                texts = listOf("caption"),
                streamUris = listOf("content://sender/photo"),
            ),
        ).rejected()
        val unsupported = service().intake(
            payload(mimeType = "application/pdf", texts = listOf("not accepted")),
        ).rejected()

        assertEquals(ShareImportRejection.MIXED_CONTENT, mixed.reason)
        assertEquals(ShareImportRejection.UNSUPPORTED_MIME_TYPE, unsupported.reason)
        assertTrue(ShareImportFileStore(root, content).loadAll().isEmpty())
        assertTrue(notifications.destinations.isEmpty())
    }

    @Test
    fun claimedSourcePackageCannotSpoofNormalizedSource() {
        val result = service().intake(
            payload(
                mimeType = "text/plain",
                texts = listOf("일반 공유 텍스트"),
                claimedSourcePackage = "com.instagram.android",
            ),
        ).accepted()

        assertEquals(ShareSource.UNKNOWN, result.stored.normalized.source)
        assertNull(result.stored.normalized.originalUrl)
        assertFalse(metadataText(result.stored).contains("com.instagram.android"))
    }

    @Test
    fun unavailableOrNonImageStreamRejectsAndCleansPartialIntake() {
        content.unavailable("content://sender/revoked", "image/jpeg")
        content.add("content://sender/spoofed", "%PDF".toByteArray(), "image/jpeg")

        assertEquals(
            ShareImportRejection.UNAVAILABLE_ATTACHMENT,
            service().intake(
                payload(mimeType = "image/jpeg", streamUris = listOf("content://sender/revoked")),
            ).rejected().reason,
        )
        assertEquals(
            ShareImportRejection.UNSUPPORTED_ATTACHMENT,
            service().intake(
                payload(mimeType = "image/jpeg", streamUris = listOf("content://sender/spoofed")),
            ).rejected().reason,
        )
        assertNoIntakeFiles()
    }

    @Test
    fun copiedFileAndMetadataSurviveRepositoryRecreation() {
        content.add("content://sender/photo", png(), "image/png")
        val accepted = service().intake(
            payload(
                mimeType = "image/png",
                texts = listOf("전주 여행 사진"),
                streamUris = listOf("content://sender/photo"),
            ),
        ).accepted()

        val reloaded = ShareImportFileStore(root, content).load(accepted.stored.local.id)!!

        assertEquals(accepted.stored.local.id, reloaded.local.id)
        assertEquals("전주 여행 사진", reloaded.normalized.title)
        assertEquals("image/png", reloaded.local.copiedAttachmentMimeTypes.single())
        assertEquals(png().toList(), reloaded.local.copiedAttachments.single().readBytes().toList())
        assertTrue(reloaded.normalized.attachmentUris.isEmpty())
    }

    @Test
    fun notificationDestinationCarriesOnlyTheReviewActionAndPersistedId() {
        val accepted = service().intake(
            payload(mimeType = "text/plain", texts = listOf("전주 객사")),
        ).accepted()

        val destination = notifications.single()
        assertEquals(ShareImportNavigation.REVIEW_ACTION, destination.action)
        assertEquals(ShareImportNavigation.IMPORT_ID_EXTRA, destination.importIdExtra)
        assertEquals(accepted.stored.local.id, destination.importId)
    }

    @Test
    fun malformedActionMissingContentAndMultipleAttachmentsOnSendAreRejected() {
        content.add("content://sender/one", jpeg(1), "image/jpeg")
        content.add("content://sender/two", jpeg(2), "image/jpeg")

        assertEquals(
            ShareImportRejection.UNSUPPORTED_ACTION,
            service().intake(payload(action = "android.intent.action.VIEW")).rejected().reason,
        )
        assertEquals(
            ShareImportRejection.MISSING_CONTENT,
            service().intake(payload(mimeType = "text/plain")).rejected().reason,
        )
        assertEquals(
            ShareImportRejection.MALFORMED_INTENT,
            service().intake(
                payload(
                    mimeType = "image/*",
                    streamUris = listOf("content://sender/one", "content://sender/two"),
                ),
            ).rejected().reason,
        )
    }

    @Test
    fun midCopyProviderSecurityFailureIsTypedCleanedAndPreservesPriorIntake() {
        val prior = service().intake(
            payload(mimeType = "text/plain", texts = listOf("이미 저장된 공유 담기")),
        ).accepted()
        val revokedSource = object : ShareImportContentSource {
            override fun mimeType(uri: String): String? = "image/jpeg"

            override fun open(uri: String): InputStream = object : InputStream() {
                private var firstRead = true

                override fun read(): Int = error("bulk read expected")

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!firstRead) throw SecurityException("permission revoked during read")
                    firstRead = false
                    val bytes = jpeg()
                    bytes.copyInto(buffer, offset)
                    return bytes.size
                }
            }
        }

        val result = ShareImportIntakeService(
            ShareImportFileStore(root, revokedSource),
            notifications,
        ).intake(
            payload(mimeType = "image/jpeg", streamUris = listOf("content://sender/mid-revoked")),
        ).rejected()

        assertEquals(ShareImportRejection.UNAVAILABLE_ATTACHMENT, result.reason)
        val remaining = ShareImportFileStore(root, content).loadAll()
        assertEquals(listOf(prior.stored.local.id), remaining.map { it.local.id })
        assertEquals(1, root.listFiles().orEmpty().count { it.isDirectory })
    }

    @Test
    fun interruptedCopyRemovesPartialFileAndStagingMetadata() {
        val interruptedSource = object : ShareImportContentSource {
            override fun mimeType(uri: String): String? = "image/jpeg"

            override fun open(uri: String): InputStream = object : InputStream() {
                private var firstRead = true

                override fun read(): Int = error("bulk read expected")

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!firstRead) throw InterruptedIOException("cancelled")
                    firstRead = false
                    val bytes = jpeg()
                    bytes.copyInto(buffer, offset)
                    return bytes.size
                }
            }
        }
        try {
            val result = ShareImportIntakeService(
                ShareImportFileStore(root, interruptedSource),
                notifications,
            ).intake(
                payload(mimeType = "image/jpeg", streamUris = listOf("content://sender/interrupted")),
            ).rejected()

            assertEquals(ShareImportRejection.UNAVAILABLE_ATTACHMENT, result.reason)
            assertTrue(Thread.currentThread().isInterrupted)
            assertNoIntakeFiles()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun oversizedSecondAttachmentRemovesEarlierCopiesAndStagingMetadata() {
        content.add("content://sender/one", jpeg(1), "image/jpeg")
        content.add("content://sender/large", jpeg() + ByteArray(64), "image/jpeg")
        val boundedStore = ShareImportFileStore(
            rootDirectory = root,
            contentSource = content,
            limits = ShareImportLimits(maxAttachmentCount = 4, maxAttachmentBytes = 16),
        )

        val result = ShareImportIntakeService(boundedStore, notifications).intake(
            payload(
                action = ShareImportActions.SEND_MULTIPLE,
                mimeType = "image/*",
                streamUris = listOf("content://sender/one", "content://sender/large"),
            ),
        ).rejected()

        assertEquals(ShareImportRejection.ATTACHMENT_TOO_LARGE, result.reason)
        assertNoIntakeFiles()
    }

    private fun service() = ShareImportIntakeService(
        ShareImportFileStore(root, content),
        notifications,
    )

    private fun payload(
        action: String = ShareImportActions.SEND,
        mimeType: String? = "text/plain",
        texts: List<String> = emptyList(),
        htmlText: String? = null,
        dataUri: String? = null,
        streamUris: List<String> = emptyList(),
        clipDataUris: List<String> = emptyList(),
        claimedSourcePackage: String? = null,
    ) = ShareImportPayload(
        action = action,
        mimeType = mimeType,
        texts = texts,
        htmlText = htmlText,
        dataUri = dataUri,
        streamUris = streamUris,
        clipDataUris = clipDataUris,
        claimedSourcePackage = claimedSourcePackage,
    )

    private fun ShareImportIntakeResult.accepted(): ShareImportIntakeResult.Accepted {
        assertTrue("Expected accepted but was $this", this is ShareImportIntakeResult.Accepted)
        return this as ShareImportIntakeResult.Accepted
    }

    private fun ShareImportIntakeResult.rejected(): ShareImportIntakeResult.Rejected {
        assertTrue("Expected rejected but was $this", this is ShareImportIntakeResult.Rejected)
        return this as ShareImportIntakeResult.Rejected
    }

    private fun RecordingShareImportNotifier.single(): ShareImportNotificationDestination =
        destinations.single()

    private fun metadataText(stored: StoredShareImport): String =
        File(stored.local.directory, "metadata.properties").readText()

    private fun assertNoIntakeFiles() {
        val files = root.walkTopDown().drop(1).toList()
        assertTrue("Expected cleanup, found $files", files.isEmpty())
    }

    private fun jpeg(marker: Int = 0): ByteArray = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), marker.toByte(), 1, 2, 3,
    )

    private fun png(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
}

private class RecordingShareImportNotifier : ShareImportNotifier {
    val destinations = mutableListOf<ShareImportNotificationDestination>()

    override fun post(destination: ShareImportNotificationDestination): Boolean {
        destinations += destination
        return true
    }
}

private class FakeShareImportContentSource : ShareImportContentSource {
    private data class Entry(
        val bytes: ByteArray?,
        val mimeType: String?,
    )

    private val entries = mutableMapOf<String, Entry>()

    fun add(uri: String, bytes: ByteArray, mimeType: String?) {
        entries[uri] = Entry(bytes, mimeType)
    }

    fun unavailable(uri: String, mimeType: String?) {
        entries[uri] = Entry(null, mimeType)
    }

    override fun mimeType(uri: String): String? = entries[uri]?.mimeType

    override fun open(uri: String): InputStream? =
        entries[uri]?.bytes?.let(::ByteArrayInputStream)
}
