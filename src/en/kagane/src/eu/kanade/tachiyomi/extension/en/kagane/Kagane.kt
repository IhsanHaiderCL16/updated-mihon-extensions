package eu.kanade.tachiyomi.extension.en.kagane

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class Kagane : HttpSource() {

    override val name = "Kagane"

    override val baseUrl = "https://kagane.to"

    override val lang = "en"

    override val supportsLatest = true

    private val apiUrl = "https://api.kagane.to"

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Origin", baseUrl)
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "views")
            .addQueryParameter("order", "desc")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<KaganeListDto>()
        val mangas = dto.data.map { it.toSManga() }
        val hasNextPage = dto.meta.currentPage < dto.meta.lastPage
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", "updated_at")
            .addQueryParameter("order", "desc")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ============================== Search ================================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/api/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("search", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    url.addQueryParameter("sort", filter.selected.value)
                    url.addQueryParameter("order", filter.order)
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url.addQueryParameter("status", filter.selected.value)
                    }
                }
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addQueryParameter("tags[]", it.value)
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ============================== Details ===============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")
        return GET("$apiUrl/api/comics/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<KaganeComicResponseDto>()
        return dto.data.toSManga()
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")
        return GET("$apiUrl/api/comics/$slug/chapters?per_page=9999&sort=number&order=asc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = response.parseAs<KaganeChapterListDto>()
        return dto.data.map { it.toSChapter() }
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl${chapter.url}"

    // ============================== Pages =================================

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        // URL pattern: /comics/{comicSlug}/chapters/{chapterSlug}
        val comicSlug = parts.getOrNull(parts.size - 3) ?: ""
        val chapterSlug = parts.lastOrNull() ?: ""
        return GET("$apiUrl/api/comics/$comicSlug/chapters/$chapterSlug/pages", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val dto = response.parseAs<KaganePagesDto>()
        return dto.data.mapIndexed { index, pageDto ->
            Page(index, imageUrl = decryptUrl(pageDto.url))
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decryptUrl(encryptedUrl: String): String {
        return try {
            val key = "kagane_secret_ke".toByteArray() // 16-byte key
            val iv = "kagane_secret_iv".toByteArray()  // 16-byte IV
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decoded = Base64.decode(encryptedUrl)
            String(cipher.doFinal(decoded))
        } catch (e: Exception) {
            encryptedUrl
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    class SortFilter : SelectFilter<SortOption>(
        "Sort by",
        listOf(
            SortOption("Views", "views"),
            SortOption("Latest Update", "updated_at"),
            SortOption("Title", "title"),
            SortOption("Created", "created_at"),
        ),
    ) {
        val order get() = if (state < options.size / 2) "desc" else "asc"
    }

    class StatusFilter : SelectFilter<StatusOption>(
        "Status",
        listOf(
            StatusOption("All", ""),
            StatusOption("Ongoing", "ongoing"),
            StatusOption("Completed", "completed"),
            StatusOption("Hiatus", "hiatus"),
        ),
    )

    class GenreFilter : Filter.Group<CheckBoxFilter>(
        "Genres",
        listOf(
            CheckBoxFilter("Action", "action"),
            CheckBoxFilter("Adventure", "adventure"),
            CheckBoxFilter("Comedy", "comedy"),
            CheckBoxFilter("Drama", "drama"),
            CheckBoxFilter("Fantasy", "fantasy"),
            CheckBoxFilter("Horror", "horror"),
            CheckBoxFilter("Isekai", "isekai"),
            CheckBoxFilter("Manhwa", "manhwa"),
            CheckBoxFilter("Mature", "mature"),
            CheckBoxFilter("Mystery", "mystery"),
            CheckBoxFilter("Romance", "romance"),
            CheckBoxFilter("School Life", "school-life"),
            CheckBoxFilter("Sci-fi", "sci-fi"),
            CheckBoxFilter("Slice of Life", "slice-of-life"),
            CheckBoxFilter("Sports", "sports"),
            CheckBoxFilter("Supernatural", "supernatural"),
            CheckBoxFilter("Thriller", "thriller"),
            CheckBoxFilter("Tragedy", "tragedy"),
            CheckBoxFilter("Webtoon", "webtoon"),
        ),
    )

    abstract class SelectFilter<T : NamedValue>(
        name: String,
        val options: List<T>,
    ) : Filter.Select<String>(name, options.map { it.name }.toTypedArray()) {
        val selected get() = options[state]
    }

    class CheckBoxFilter(name: String, val value: String) : Filter.CheckBox(name)
    data class SortOption(override val name: String, override val value: String) : NamedValue
    data class StatusOption(override val name: String, override val value: String) : NamedValue

    interface NamedValue {
        val name: String
        val value: String
    }
}

// ============================== URL Activity ==============================

class KaganeUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size >= 2) {
            val slug = pathSegments.last()
            val searchQuery = "slug:$slug"
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                putExtra("query", searchQuery)
                putExtra("filter", packageName)
            }
            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("KaganeUrlActivity", e.toString())
            }
        } else {
            Log.e("KaganeUrlActivity", "Could not parse URI from intent $intent")
        }
        finish()
        overridePendingTransition(0, 0)
    }
}

// ============================== DTOs ======================================

@Serializable
data class KaganeListDto(
    val data: List<KaganeComicDto>,
    val meta: KaganeMetaDto,
)

@Serializable
data class KaganeComicResponseDto(
    val data: KaganeComicDto,
)

@Serializable
data class KaganeComicDto(
    val slug: String,
    val title: String,
    val cover: String? = null,
    val synopsis: String? = null,
    val status: String? = null,
    val authors: List<KaganeAuthorDto>? = null,
    val genres: List<KaganeGenreDto>? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "/comics/$slug"
        title = this@KaganeComicDto.title
        thumbnail_url = cover
        description = synopsis
        status = when (this@KaganeComicDto.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        author = authors?.joinToString(", ") { it.name }
        genre = genres?.joinToString(", ") { it.name }
        initialized = authors != null
    }
}

@Serializable
data class KaganeAuthorDto(val name: String)

@Serializable
data class KaganeGenreDto(val name: String)

@Serializable
data class KaganeMetaDto(
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
data class KaganeChapterListDto(
    val data: List<KaganeChapterDto>,
)

@Serializable
data class KaganeChapterDto(
    val slug: String,
    val number: Float,
    val title: String? = null,
    @SerialName("comic_slug") val comicSlug: String,
    @SerialName("created_at") val createdAt: String? = null,
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US)

    fun toSChapter() = SChapter.create().apply {
        url = "/comics/$comicSlug/chapters/$slug"
        val num = if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()
        name = buildString {
            append("Chapter $num")
            if (!title.isNullOrBlank()) append(": $title")
        }
        date_upload = createdAt?.let { dateFormat.tryParse(it) } ?: 0L
        chapter_number = number
    }
}

@Serializable
data class KaganePagesDto(
    val data: List<KaganePageDto>,
)

@Serializable
data class KaganePageDto(
    val url: String,
)
