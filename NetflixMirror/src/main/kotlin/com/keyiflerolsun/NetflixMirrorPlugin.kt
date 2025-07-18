package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NetflixMirrorPlugin: Plugin() {
    override fun load(context: Context) {
        NetflixMirrorStorage.init(context.applicationContext)
        registerMainAPI(NetflixMirror())
        registerMainAPI(PrimeVideoMirror())
        registerMainAPI(DisneyPlusProvider())
    }
}