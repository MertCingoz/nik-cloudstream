// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class UgurFilm : MainAPI() {
    override var mainUrl             = "https://ugurfilm9.com"
    override var name                = "UgurFilm"
    override val hasMainPage         = true
    override var lang                = "tr"
    override val hasQuickSearch      = false
    override val supportedTypes      = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/turkce-altyazili-filmler/page/" to "Türkçe Altyazılı Filmler",
        "${mainUrl}/yerli-filmler/page/"            to "Yerli Filmler",
        "${mainUrl}/en-cok-izlenen-filmler/page/"   to "En Çok İzlenen Filmler",
        "${mainUrl}/category/kisa-film/page/"      to "Kısa Film",
        "${mainUrl}/category/aksiyon/page/"        to "Aksiyon",
        "${mainUrl}/category/bilim-kurgu/page/"    to "Bilim Kurgu",
        "${mainUrl}/category/belgesel/page/"       to "Belgesel",
        "${mainUrl}/category/komedi/page/"         to "Komedi",
        "${mainUrl}/category/kara-film/page/"      to "Kara Film",
        "${mainUrl}/category/erotik/page/"         to "Erotik",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.icerik div").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("span:nth-child(1)")?.text()?.trim() ?: return null
        val href      = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document

        return document.select("div.icerik div").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // BAŞLIK SEÇİCİSİ GÜNCELLENDİ: Daha önce 'h2' idi, şimdi 'h1'.
        val title          = document.selectFirst("div.bilgi h1")?.text()?.trim() ?: return null
        val poster         = fixUrlNull(document.selectFirst("div.resim img")?.attr("src"))
        val year           = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val description    = document.selectFirst("div.slayt-aciklama")?.text()?.trim()
        val tags           = document.select("p.tur a[href*='/category/']").map { it.text() }
        val rating         = document.selectFirst("span.puan")?.text()?.split(" ")?.last()
        // SÜRE SEÇİCİSİ GÜNCELLENDİ: XPath yerine daha kararlı bir CSS seçicisi kullanıldı.
        val duration       = document.selectFirst("p:contains(Süre:) b")?.text()?.split(" ")?.firstOrNull()?.trim()?.toIntOrNull()
        val actors         = document.select("li.oyuncu-k").map {
            Actor(it.selectFirst("span")!!.text(), it.selectFirst("img")?.attr("src"))
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.score     = Score.from10(rating)
            this.duration  = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("UGF", "data » $data")
        val mainDocument = app.get(data).document

        mainDocument.select("li.parttab a").forEach {
            val subUrl  = fixUrlNull(it.attr("href")) ?: return false
            val document = app.get(subUrl).document

            val iframe   = fixUrlNull(document.selectFirst("div#vast iframe")?.attr("src")) ?: return@forEach
            Log.d("UGF", "iframe » $iframe")

            if (iframe.contains(mainUrl)) {
                // KAYNAK SEÇİCİSİ VE AJAX YANITI KONTROLÜ GEREKLİ!
                // Eğer hata hala devam ediyorsa, bu bölümdeki seçicinin veya AjaxSource yapısının değiştiğini gösterir.
                // Lütfen önceki açıklamalarda belirttiğim gibi ugurfilm9.com/player/play.php?vid=... URL'sini tarayıcınızda açıp
                // Geliştirici Araçları (F12) ile "Elements" ve "Network" sekmelerini kontrol edin.
                // li.c-dropdown__item hala doğru mu?
                val kaynaklar = app.get(iframe, referer=data).document.select("li.c-dropdown__item").associate { kaynak ->
                    kaynak.attr("data-dropdown-value") to kaynak.attr("data-order-value")
                }
                Log.d("UGF", "kaynaklar » $kaynaklar")

                val vidId    = iframe.substringAfter("/play.php?vid=").trim()
                Log.d("UGF", "vidId » $vidId")

                val yuklenenler = mutableListOf<String>()

                for ((kaynak, order) in kaynaklar) {
                    Log.d("UGF", "kaynak » $kaynak | order » $order")

                    val playerApi = app.post(
                        "${mainUrl}/player/ajax_sources.php",
                        data = mapOf(
                            "vid"         to vidId,
                            "alternative" to kaynak,
                            "ord"         to order
                        )
                    ).text
                    val playerData = AppUtils.tryParseJson<AjaxSource>(playerApi) ?: continue
                    Log.d("UGF", "playerData » $playerData")

                    if (playerData.iframe in yuklenenler) continue

                    yuklenenler.add(playerData.iframe)
                    loadExtractor(playerData.iframe, "${mainUrl}/", subtitleCallback, callback)
                }
            } else {
                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}