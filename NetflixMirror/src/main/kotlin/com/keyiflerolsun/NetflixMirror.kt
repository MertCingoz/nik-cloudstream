package com.keyiflerolsun

import com.keyiflerolsun.entities.EpisodesData
import com.keyiflerolsun.entities.PlayList
import com.keyiflerolsun.entities.PostData
import com.keyiflerolsun.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.getQualityFromName
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup

class NetflixMirror : MainAPI() {
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    override var lang = "en"

    override var mainUrl = "https://netfree2.cc"
    override var name = "NetflixMirror"

    override val hasMainPage = true
    private var cookie_value = ""
    private val headers = mapOf(
        "X-Requested-With" to "XMLHttpRequest"
    )

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 50L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 50L  // ? 0.05 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment") || doc.html().contains("Bir dakika lütfen")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "nf",
            "hd" to "on"
        )
        val document = app.get("$mainUrl/mobile/home", cookies = cookies, interceptor = interceptor).document
        val items = document.select(".tray-container, #top10").map {
            it.toHomePageList()
        }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull {
            it.toSearchResult()
        }
        return HomePageList(name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = selectFirst("a")?.attr("data-post") ?: attr("data-post") ?: return null
        val posterUrl =
            fixUrlNull(selectFirst(".card-img-container img, .top10-img img")?.attr("data-src"))

        return newAnimeSearchResponse("", Id(id).toJson()) {
            this.posterUrl = posterUrl
            posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on"
        )
        val url = "$mainUrl/mobile/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = app.get(url, referer = "$mainUrl/", cookies = cookies, interceptor = interceptor).parsed<SearchData>()

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://img.nfmirrorcdn.top/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val id = parseJson<Id>(url).id
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on"
        )
        val data = app.get(
            "$mainUrl/mobile/post.php?id=$id&t=${APIHolder.unixTime}", headers, referer = "$mainUrl/", cookies = cookies,
            interceptor = interceptor).parsed<PostData>()

        val episodes = arrayListOf<Episode>()

        val title = data.title
        val castList = data.cast?.split(",")?.map { it.trim() } ?: emptyList()
        val cast = castList.map {
            ActorData(
                Actor(it),
            )
        }
        val genre = listOf(data.ua.toString()) + (data.genre?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList())
        val rating = data.match?.replace("IMDb ", "")?.toRatingInt()
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        if (data.episodes.first() == null) {
            episodes.add(newEpisode(LoadData(title, id)) {
                name = data.title
            })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://img.nfmirrorcdn.top/epimg/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }

            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(title, url, data.nextPageSeason!!, 2))
            }

            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(title, url, it.id, 1))
            }
        }

        val type = if (data.episodes.first() == null) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://img.nfmirrorcdn.top/poster/v/$id.jpg"
            backgroundPosterUrl ="https://img.nfmirrorcdn.top/poster/h/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/")
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            this.rating = rating
            this.duration = runTime
        }
    }

    private suspend fun getEpisodes(
        title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on"
        )
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers,
                referer = "$mainUrl/",
                cookies = cookies,
                interceptor = interceptor
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://img.nfmirrorcdn.top/epimg/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) break
            pg++
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (title, id) = parseJson<LoadData>(data)
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on"
        )
        val playlist = app.get(
            "$mainUrl/mobile/playlist.php?id=$id&t=$title&tm=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/",
            cookies = cookies,
            interceptor = interceptor
        ).parsed<PlayList>()

        playlist.forEach { item ->
            item.sources.forEach {
                callback.invoke(
                    newExtractorLink(
                        name,
                        it.label,
                        fixUrl(it.file),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQualityFromName(it.file.substringAfter("q=", ""))
                    }
                )
            }

            item.tracks?.filter { it.kind == "captions" }?.map { track ->
                subtitleCallback.invoke(
                    SubtitleFile(
                        track.label.toString(),
                        httpsify(track.file.toString())
                    )
                )
            }
        }

        return true
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.toString().contains(".m3u8")) {
                    val newRequest = request.newBuilder()
                        .header("Cookie", "hd=on")
                        .build()
                    return chain.proceed(newRequest)
                }
                return chain.proceed(request)
            }
        }
    }

    data class Id(
        val id: String
    )

    data class LoadData(
        val title: String, val id: String
    )
}