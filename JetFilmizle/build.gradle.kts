version = 30

cloudstream {
    authors     = listOf("keyiflerolsun", "nikyokki")
    language    = "tr"
    description = "Binlerce Film İzleme Seçeneğiyle En İyi Film İzleme Sitesi"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://jetfilmizle.de&sz=%size%"
}