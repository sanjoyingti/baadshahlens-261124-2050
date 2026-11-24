package com.aibuilt.baadshahlens.nav

import java.net.URLDecoder
import java.net.URLEncoder

sealed class Route(val path: String) {
    data object Legal : Route("legal")
    data object Camera : Route("camera")
    data object Record : Route("record")
    data object Gallery : Route("gallery")
    data object Settings : Route("settings")
    data object Downloads : Route("downloads")
    data object About : Route("about")

    data object GalleryDetail : Route("gallery_detail/{itemId}") {
        const val ARG_ID = "itemId"
        fun of(itemId: Long): String = "gallery_detail/$itemId"
    }

    data object Result : Route("result?before={before}&after={after}&mask={mask}&occ={occ}&tier={tier}") {
        const val ARG_BEFORE = "before"
        const val ARG_AFTER = "after"
        const val ARG_MASK = "mask"
        const val ARG_OCC = "occ"
        const val ARG_TIER = "tier"

        fun of(before: String, after: String, mask: String, occ: String, tier: String): String =
            "result?before=${enc(before)}&after=${enc(after)}&mask=${enc(mask)}&occ=${enc(occ)}&tier=${enc(tier)}"

        private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun decode(value: String?): String = value?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
    }
}
