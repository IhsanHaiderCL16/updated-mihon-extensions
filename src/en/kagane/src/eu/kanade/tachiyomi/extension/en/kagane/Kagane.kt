package eu.kanade.tachiyomi.extension.en.kagane

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.en.kagane.wv.Cdm
import eu.kanade.tachiyomi.extension.en.kagane.wv.ProtectionSystemHeaderBox
import eu.kanade.tachiyomi.extension.en.kagane.wv.parsePssh
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.brotli.BrotliInterceptor
import okhttp3.internal.closeQuietly
import okio.IOException
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Kagane : HttpSource(), ConfigurableSource {

    override val name = "Kagane"

    private val domain = "kagane.org"

    // Web
    override val baseUrl = "https://$domain"

    // API v2 host
    // Search/details/chapters come from here
    private val apiUrl = "https://api.$domain"

    // Reader host (the one you captured in DevTools)
    private val readerUrl = "https://yuzuki.$domain"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // Used by duplicate logic
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor(ImageInterceptor())
        .addInterceptor(::refreshTokenInterceptor)
        // fix disk cache
        .apply {
            val index = networkInterceptors().indexOfFirst { it is BrotliInterceptor }
            if (index >= 0) interceptors().add(networkInterceptors().removeAt(index))
        }
        .build()

    // ============================== Headers ==============================

    private val apiHeaders = headers.newBuilder().apply {
        add("Origin", baseUrl)
        add("Referer", "$baseUrl/")
    }.build()

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int) =
        searchMangaRequest(
            page,
            "",
            FilterList(
                SortFilter(Filter.Sort.Selection(1, false)),
                ContentRatingFilter(preferences.contentRating.toSet()),
                GenresFilter(emptyList()),
            ),
        )

    override fun popularMangaParse(response: Response) = searchMangaParse(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) =
        searchMangaRequest(
            page,
            "",
            FilterList(
                SortFilter(Filter.Sort.Selection(6, false)),
                ContentRatingFilter(preferences.contentRating.toSet()),
                GenresFilter(emptyList()),
            ),
        )

    override fun latestUpdatesParse(response: Response) = searchMangaParse(response)

    // =============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = buildJsonObject {
            filters.forEach { filter ->
                when (filter) {
                    is GenresFilter -> filter.addToJsonObject(this, preferences.excludedGenres.toList())
                    is JsonFilter -> filter.addToJsonObject(this)
                    else -> {}
                }
            }
        }.toJsonString().toRequestBody("application/json".toMediaType())

        val url = "$apiUrl/api/v2/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", (page - 1).toString())
            addQueryParameter("size", 35.toString())
            if (query.isNotBlank()) addQueryParameter("name", query)

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        filter.toUriPart().takeIf { it.isNotEmpty() }
                            ?.let { addQueryParameter("sort", it) }
                            ?: run {
                                if (query.isBlank()) addQueryParameter("sort", "updated_at,desc")
                            }
                    }
                    else -> {}
                }
            }

            addQueryParameter("scanlations", preferences.showScanlations.toString())
        }.build()

        return POST(url.toString(), headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val dto = response.parseAs<SearchDto>()

        val mangas = dto.content.filter {
            if (!preferences.showDuplicates) {
                val alternateSeries =
                    client.newCall(GET("$apiUrl/api/v2/alternate_series/${it.id}", apiHeaders))
                        .execute()
                        .parseAs<List<AlternateSeries>>()

                if (alternateSeries.isEmpty()) return@filter true

                val date = dateFormat.tryParse(it.publishedOn)
                for (alt in alternateSeries) {
                    val altDate = dateFormat.tryParse(alt.publishedOn)

                    when {
                        it.booksCount < alt.booksCount -> return@filter false
                        it.booksCount == alt.booksCount -> {
                            if (date > altDate) return@filter false
                        }
                    }
                }
                true
            } else {
                true
            }
        }.map { it.toSManga(apiUrl, preferences.showSource) }

        return MangasPage(mangas, hasNextPage = dto.hasNextPage())
    }

    // =========================== Manga Details ============================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$apiUrl/api/v2/series/${manga.url}", apiHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<DetailsDto>()
        return dto.toSManga()
    }

    override fun getMangaUrl(manga: SManga): String {
        return "$baseUrl/series/${manga.url}"
    }

    // ============================== Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$apiUrl/api/v2/books/${manga.url}", apiHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val seriesId = response.request.url.toString().substringAfterLast("/")
        val dto = response.parseAs<ChapterDto>()

        val source = runCatching {
            client.newCall(GET("$apiUrl/api/v2/series/$seriesId", apiHeaders))
                .execute()
                .parseAs<DetailsDto>()
                .source
        }.getOrDefault("")

        val useSourceChapterNumber = source in setOf(
            "Dark Horse Comics",
            "Flame Comics",
            "MangaDex",
            "Square Enix Manga",
        )

        return dto.content.map { it.toSChapter(useSourceChapterNumber) }.reversed()
    }

    // =============================== Reader ===============================

    private var cacheUrl = "https://akari.$domain"
    private var accessToken: String = ""

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        // url = "$seriesId;$bookId;$pagesCount"
        if (chapter.url.count { it == ';' } != 2) {
            throw Exception("Chapter url error, please refresh chapter list.")
        }

        val (seriesId, bookId, _) = chapter.url.split(";")

        val reader = getReaderResponse(bookId)

        accessToken = reader.accessToken
        cacheUrl = reader.cacheUrl

        val isDataSaver = preferences.dataSaver

        val pages = reader.pages
            .sortedBy { it.pageNumber }
            .mapIndexed { index, p ->
                val pageUrl = "${cacheUrl.trimEnd('/')}/api/v2/books/file/$bookId/${p.pageUuid}".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("token", accessToken)
                    .addQueryParameter("is_datasaver", isDataSaver.toString())
                    .build()
                    .toString()

                Page(index, imageUrl = pageUrl)
            }

        return Observable.just(pages)
    }

    private fun getReaderResponse(bookId: String): ReaderDto {
        val url = "$readerUrl/api/v2/books/$bookId".toHttpUrl().newBuilder().apply {
            addQueryParameter("is_datasaver", preferences.dataSaver.toString())
        }.build()

        // IMPORTANT: This request is POST in the browser capture
        return client.newCall(POST(url.toString(), apiHeaders)).execute()
            .parseAs<ReaderDto>()
    }

    // ========================== Token refresh ============================

    private fun refreshTokenInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (!url.queryParameterNames.contains("token")) {
            return chain.proceed(request)
        }

        val path = url.encodedPathSegments

        // /api/v2/books/file/{bookId}/{pageUuid}
        val bookId = path.getOrNull(4).orEmpty()

        var response = chain.proceed(
            request.newBuilder()
                .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                .build(),
        )

        if (response.code == 401) {
            response.close()

            val reader = try {
                getReaderResponse(bookId)
            } catch (_: Exception) {
                throw IOException("Failed to retrieve token")
            }

            accessToken = reader.accessToken
            cacheUrl = reader.cacheUrl

            response = chain.proceed(
                request.newBuilder()
                    .url(url.newBuilder().setQueryParameter("token", accessToken).build())
                    .build(),
            )
        }

        return response
    }

    // ============================= Unsupported ============================

    override fun pageListParse(response: Response): List<Page> {
        throw UnsupportedOperationException()
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================ Preferences =============================

    private val SharedPreferences.contentRating: List<String>
        get() {
            val maxRating = this.getString(CONTENT_RATING, CONTENT_RATING_DEFAULT)
            val index = CONTENT_RATINGS.indexOfFirst { it == maxRating }
            return CONTENT_RATINGS.slice(0..index.coerceAtLeast(0))
        }

    private val SharedPreferences.excludedGenres: Set<String>
        get() = this.getStringSet(GENRES_PREF, emptySet()) ?: emptySet()

    private val SharedPreferences.showScanlations: Boolean
        get() = this.getBoolean(SHOW_SCANLATIONS, SHOW_SCANLATIONS_DEFAULT)

    private val SharedPreferences.showSource: Boolean
        get() = this.getBoolean(SHOW_SOURCE, SHOW_SOURCE_DEFAULT)

    private val SharedPreferences.showDuplicates: Boolean
        get() = this.getBoolean(SHOW_DUPLICATES, SHOW_DUPLICATES_DEFAULT)

    private val SharedPreferences.dataSaver
        get() = this.getBoolean(DATA_SAVER, false)

    private val SharedPreferences.wvd
        get() = this.getString(WVD_KEY, WVD_DEFAULT)!!

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = CONTENT_RATING
            title = "Content Rating"
            entries = CONTENT_RATINGS.map { it.replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
            entryValues = CONTENT_RATINGS
            summary = "%s"
            setDefaultValue(CONTENT_RATING_DEFAULT)
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = GENRES_PREF
            title = "Exclude Genres"
            entries = GenresList.map { it.replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
            entryValues = GenresList
            summary = preferences.excludedGenres.joinToString { it.replaceFirstChar { c -> c.uppercase() } }
            setDefaultValue(emptySet<String>())

            setOnPreferenceChangeListener { _, values ->
                val selected = values as Set<String>
                this.summary = selected.joinToString { it.replaceFirstChar { c -> c.uppercase() } }
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_SCANLATIONS
            title = "Show scanlations"
            setDefaultValue(SHOW_SCANLATIONS_DEFAULT)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_DUPLICATES
            title = "Show duplicates"
            summary = "Show duplicate entries.\nPicks the entry with most chapters if disabled\nThis switch isn't always accurate\nNOTE: Enabling this option will slow your search speed down"
            setDefaultValue(SHOW_DUPLICATES_DEFAULT)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_SOURCE
            title = "Show source name"
            summary = "Show source name in title"
            setDefaultValue(SHOW_SOURCE_DEFAULT)
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = DATA_SAVER
            title = "Data saver"
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = WVD_KEY
            title = "WVD file"
            summary = "Enter contents as base64 string"
            setDefaultValue(WVD_DEFAULT)
        }.let(screen::addPreference)
    }

    companion object {
        private const val CONTENT_RATING = "pref_content_rating"
        private const val CONTENT_RATING_DEFAULT = "pornographic"
        internal val CONTENT_RATINGS = arrayOf(
            "safe",
            "suggestive",
            "erotica",
            "pornographic",
        )

        private const val GENRES_PREF = "pref_genres_exclude"
        private const val SHOW_SCANLATIONS = "pref_show_scanlations"
        private const val SHOW_SCANLATIONS_DEFAULT = true

        private const val SHOW_SOURCE = "pref_show_source"
        private const val SHOW_SOURCE_DEFAULT = false

        private const val SHOW_DUPLICATES = "pref_show_duplicates"
        private const val SHOW_DUPLICATES_DEFAULT = true

        private const val DATA_SAVER = "data_saver_default"

        private const val WVD_KEY = "wvd_key"
        private const val WVD_DEFAULT = ""
    }

    // ============================= Filters ==============================

    private val metadataClient = client.newBuilder()
        .addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).newBuilder()
                .header("Cache-Control", "max-age=${24 * 60 * 60}")
                .removeHeader("Pragma")
                .removeHeader("Expires")
                .build()
        }.build()

    override fun getFilterList(): FilterList = runBlocking(Dispatchers.IO) {
        val filters: MutableList<Filter<*>> = mutableListOf(
            SortFilter(),
            ContentRatingFilter(preferences.contentRating.toSet()),
            Filter.Separator(),
        )

        val response = metadataClient.newCall(
            GET("$apiUrl/api/v2/metadata", apiHeaders, CacheControl.FORCE_CACHE),
        ).await()

        if (!response.isSuccessful) {
            metadataClient.newCall(
                GET("$apiUrl/api/v2/metadata", apiHeaders, CacheControl.FORCE_NETWORK),
            ).enqueue(
                object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        response.closeQuietly()
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        Log.e(name, "Failed to fetch filters", e)
                    }
                },
            )

            filters.addAll(
                index = 0,
                listOf(
                    Filter.Header("Press 'Reset' to load more filters"),
                    Filter.Separator(),
                ),
            )

            return@runBlocking FilterList(filters)
        }

        val metadata = try {
            response.parseAs<MetadataDto>()
        } catch (e: Throwable) {
            Log.e(name, "Failed to parse filters", e)

            filters.addAll(
                index = 0,
                listOf(
                    Filter.Header("Failed to parse additional filters"),
                    Filter.Separator(),
                ),
            )
            return@runBlocking FilterList(filters)
        }

        filters.addAll(
            index = 2,
            listOf(
                GenresFilter(metadata.getGenresList()),
                TagsFilter(metadata.getTagsList()),
                SourcesFilter(metadata.getSourcesList().filter {
                    !(!preferences.showScanlations && !officialSources.contains(it.name.lowercase()))
                }),
            ),
        )

        FilterList(filters)
    }
}
