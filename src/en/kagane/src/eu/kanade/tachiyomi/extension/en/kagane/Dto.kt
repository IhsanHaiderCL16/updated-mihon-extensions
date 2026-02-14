package eu.kanade.tachiyomi.extension.en.kagane

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale

// ============================== Search ==============================
// POST https://api.kagane.org/api/v2/search?page=0&size=35&name=...
@Serializable
class SearchDto(
    val content: List<Book>,
    val last: Boolean,
) {
    fun hasNextPage() = !last

    @Serializable
    class Book(
        @SerialName("series_id")
        val id: String,

        val title: String,

        @SerialName("source_name")
        val source: String,

        @SerialName("book_count")
        val booksCount: Int,

        @SerialName("published_on")
        val publishedOn: String? = null,
    ) {
        fun toSManga(apiBase: String, showSource: Boolean): SManga = SManga.create().apply {
            title = if (showSource) "${this@Book.title.trim()} [$source]" else this@Book.title
            url = id
            thumbnail_url = "$apiBase/api/v2/series/$id/thumbnail"
        }
    }
}

// Used by the "remove duplicates" logic
@Serializable
class AlternateSeries(
    @SerialName("book_count")
    val booksCount: Int,

    @SerialName("published_on")
    val publishedOn: String? = null,
)

// =========================== Manga details ===========================
// GET https://api.kagane.org/api/v2/series/{seriesId}
@Serializable
class DetailsDto(
    @SerialName("source_name")
    val source: String,

    val authors: List<String> = emptyList(),

    val status: String = "",

    val synopsis: String? = null,

    val genres: List<String> = emptyList(),

    @SerialName("alternate_titles")
    val alternateTitles: List<AlternateTitles> = emptyList(),
) {
    @Serializable
    class AlternateTitles(
        val title: String,
    )

    fun toSManga(): SManga = SManga.create().apply {
        val desc = StringBuilder()

        if (!synopsis.isNullOrBlank()) desc.append(synopsis).append("\n\n")
        desc.append("Source: ").append(source).append("\n\n")

        if (alternateTitles.isNotEmpty()) {
            desc.append("Associated Name(s):\n\n")
            alternateTitles.forEach { desc.append("• ${it.title}\n") }
        }

        author = authors.joinToString()
        description = desc.toString()
        genre = (listOf(source) + genres).joinToString()
        status = this@DetailsDto.status.toStatus()
    }

    private fun String.toStatus(): Int = when (this) {
        "ONGOING" -> SManga.ONGOING
        "ENDED" -> SManga.COMPLETED
        "HIATUS" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }
}

// ============================= Chapters ==============================
// GET https://api.kagane.org/api/v2/books/{seriesId}
@Serializable
class ChapterDto(
    val content: List<Book>,
) {
    @Serializable
    class Book(
        @SerialName("book_id")
        val id: String,

        @SerialName("series_id")
        val seriesId: String,

        val title: String,

        @SerialName("page_count")
        val pagesCount: Int,

        @SerialName("sort_no")
        val number: Float,

        @SerialName("published_on")
        val publishedOn: String? = null,

        @SerialName("created_at")
        val createdAt: String? = null,
    ) {
        fun toSChapter(useSourceChapterNumber: Boolean = false): SChapter = SChapter.create().apply {
            // Keep pagesCount in URL for compatibility with older logic
            url = "$seriesId;$id;$pagesCount"
            name = title

            // Prefer published_on, fallback to created_at
            date_upload = dateFormat.tryParse(publishedOn ?: createdAt)

            chapter_number = if (useSourceChapterNumber) number else -1f
        }

        companion object {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
        }
    }
}

// =============================== Reader ==============================
// POST https://yuzuki.kagane.org/api/v2/books/{bookId}?is_datasaver=false
@Serializable
class ReaderDto(
    @SerialName("access_token")
    val accessToken: String,

    @SerialName("cache_url")
    val cacheUrl: String,

    val pages: List<PageDto>,
) {
    @Serializable
    class PageDto(
        @SerialName("page_number")
        val pageNumber: Int,

        @SerialName("page_uuid")
        val pageUuid: String,

        val format: String,
    )
}
