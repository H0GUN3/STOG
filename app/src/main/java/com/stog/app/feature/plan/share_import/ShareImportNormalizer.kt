package com.stog.app.feature.plan.share_import

data class ShareImportPayload(
    val action: String?,
    val mimeType: String?,
    val texts: List<String>,
    val htmlText: String?,
    val dataUri: String?,
    val streamUris: List<String>,
    val clipDataUris: List<String>,
    val claimedSourcePackage: String? = null,
)

enum class ShareSource {
    KAKAO,
    NAVER,
    INSTAGRAM,
    UNKNOWN,
}

data class PlaceMention(
    val title: String,
    val address: String?,
)

data class NormalizedShareImport(
    val source: ShareSource,
    val title: String?,
    val address: String?,
    val originalUrl: String?,
    val mentions: List<PlaceMention>,
    val attachmentUris: List<String>,
)

object ShareImportNormalizer {
    private val urlPattern = Regex("""https?://[^\s\"'<>]+""")
    private val htmlTagPattern = Regex("""<[^>]+>""")

    fun normalize(payload: ShareImportPayload): NormalizedShareImport {
        val plainLines = payload.texts.normalizedLines()
        val fallbackLines = if (plainLines.isEmpty()) {
            payload.htmlText
                ?.replace(Regex("""(?i)<br\s*/?>|</p>|</div>|</li>"""), "\n")
                ?.replace(htmlTagPattern, " ")
                ?.decodeBasicHtmlEntities()
                ?.let { listOf(it).normalizedLines() }
                .orEmpty()
        } else {
            emptyList()
        }
        val lines = plainLines.ifEmpty { fallbackLines }
        val combinedText = buildString {
            append(lines.joinToString("\n"))
            payload.htmlText?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
            payload.dataUri?.takeIf(::isWebUri)?.let {
                if (isNotEmpty()) append('\n')
                append(it)
            }
        }
        val source = detectSource(combinedText)
        val originalUrl = urlPattern.find(combinedText)?.value
        val titleLines = lines
            .map { urlPattern.replace(it, "").trim() }
            .filter(String::isNotEmpty)
        val title = titleLines.firstOrNull()
            ?.removePrefix("[카카오맵] ")
            ?.removePrefix("[네이버지도] ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val address = titleLines.drop(1).firstOrNull()?.takeIf(::looksLikeAddress)
        val mentions = when {
            source == ShareSource.INSTAGRAM -> titleLines.map { PlaceMention(it, null) }
            title != null -> listOf(PlaceMention(title, address))
            else -> emptyList()
        }

        return NormalizedShareImport(
            source = source,
            title = title,
            address = address,
            originalUrl = originalUrl,
            mentions = mentions,
            attachmentUris = (
                payload.streamUris +
                    payload.clipDataUris +
                    listOfNotNull(payload.dataUri?.takeIf(::isContentUri))
                ).distinct(),
        )
    }

    private fun List<String>.normalizedLines(): List<String> =
        asSequence()
            .flatMap { it.lineSequence() }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()

    private fun detectSource(text: String): ShareSource = when {
        "kko.to" in text || "[카카오맵]" in text -> ShareSource.KAKAO
        "naver.me" in text || "[네이버지도]" in text -> ShareSource.NAVER
        "instagram.com" in text -> ShareSource.INSTAGRAM
        else -> ShareSource.UNKNOWN
    }

    private fun looksLikeAddress(line: String): Boolean =
        line.contains("시") || line.contains("군") || line.contains("구") ||
            line.contains("로") || line.contains("길")

    private fun isContentUri(value: String): Boolean =
        value.startsWith("content:", ignoreCase = true)

    private fun isWebUri(value: String): Boolean =
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)

    private fun String.decodeBasicHtmlEntities(): String =
        replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
}
