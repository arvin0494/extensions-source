package eu.kanade.tachiyomi.extension.all.mkissa

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.i18n.Intl
import keiyoushi.network.rateLimit
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Source
abstract class MKissa : HttpSource(), ConfigurableSource {

    override val supportsLatest: Boolean = true

    override val client = network.client.newBuilder()
        .rateLimit(3)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val intl = Intl(
        language = lang,
        baseLanguage = "en",
        availableLanguages = setOf("en"),
        classLoader = this::class.java.classLoader!!,
    )

    private val preferences by getPreferencesLazy()

    // ============================== Popular ==============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/manga?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        // This is a placeholder - actual selectors need to be determined from the site
        val entries = document.select("a[href^='/manga/']").distinct().map {
            searchMangaFromElement(it)
        }

        val hasNextPage = document.select("a[href*='page=']").isNotEmpty()

        return MangasPage(entries, hasNextPage)
    }

    private fun searchMangaFromElement(element: Element) = SManga.create().apply {
        val link = element.attr("href")
        url = link
        title = element.selectFirst("img")?.attr("alt") ?: element.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/manga?page=$page&sort=updated", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ============================== Search ===============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search/manga".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        url.addQueryParameter("page", page.toString())

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("http://") || query.startsWith("https://")) {
            val url = query.toHttpUrlOrNull()
            if (url != null && url.host == baseUrl.toHttpUrl().host) {
                val path = url.pathSegments
                if (path.size >= 2 && path[0] == "manga") {
                    val slug = path[1]
                    val manga = SManga.create().apply {
                        this.url = "/manga/$slug/"
                    }
                    return fetchMangaDetails(manga).map { MangasPage(listOf(it), false) }
                }
                throw Exception("Unsupported URL")
            }
        }
        return super.fetchSearchManga(page, query, filters)
    }

    // ============================== Details ==============================

    override fun mangaDetailsParse(response: Response) = SManga.create().apply {
        val document = response.asJsoup()

        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h1")?.text() ?: ""
        description = document.selectFirst("meta[property='og:description']")?.attr("content") ?: ""
        author = document.selectFirst("meta[property='og:novel:author']")?.attr("content") ?: ""
        artist = author
        thumbnail_url = document.selectFirst("meta[property='og:image']")?.attr("content") ?: ""

        // Try to determine status from meta tags or content
        val statusText = document.select("meta[property='og:novel:status']")?.attr("content") ?: ""
        status = when (statusText.lowercase()) {
            "ongoing", "publishing" -> SManga.ONGOING
            "completed", "finished" -> SManga.COMPLETED
            "hiatus", "on hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    // ============================= Chapters ==============================

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl$manga.url", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        // Placeholder - needs actual implementation based on site structure
        val chapters = document.select("a[href*='/chapter/'], a[href*='/read/']").mapIndexed { index, element ->
            SChapter.create().apply {
                url = element.attr("href")
                name = element.text()
                chapter_number = (index + 1).toFloat()
            }
        }

        return chapters.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    // =============================== Pages ===============================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl$chapter.url", headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        // Placeholder - actual implementation needed
        val pages = document.select("img[src*='.jpg'], img[src*='.png'], img[src*='.webp']").mapIndexed { index, element ->
            Page(index, element.attr("src"))
        }

        return pages
    }

    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    // ============================== Filters ==============================

    override fun getFilterList() = FilterList()

    // ============================= Utilities =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_WEBVIEW_DECRYPT
            title = "Use WebView for page decryption"
            summary = "Enable if images fail to load (slower but handles protected images)"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private val useWebViewDecrypt by lazy {
        preferences.getBoolean(PREF_WEBVIEW_DECRYPT, false)
    }

    companion object {
        private const val PREF_WEBVIEW_DECRYPT = "pref_webview_decrypt"
    }
}