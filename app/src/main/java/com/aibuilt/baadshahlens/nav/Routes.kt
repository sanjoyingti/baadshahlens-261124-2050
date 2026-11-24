package com.aibuilt.baadshahlens.nav

sealed class Route(val path: String) {
    data object Legal : Route("legal")
    data object Camera : Route("camera")
    data object Record : Route("record")
    data object Gallery : Route("gallery")
    data object Settings : Route("settings")
    data object Downloads : Route("downloads")
    data object About : Route("about")
    data object GalleryDetail : Route("gallery_detail/{itemId}") {
        fun of(itemId: Long): String = "gallery_detail/$itemId"
    }
    data object Result : Route("result?path={path}&mask={mask}&tier={tier}") {
        fun of(path: String, mask: String, tier: String): String =
            "result?path=${java.net.URLEncoder.encode(path, "UTF-8")}" +
                "&mask=${java.net.URLEncoder.encode(mask, "UTF-8")}&tier=$tier"
        const val ARG_PATH = "path"
        const val ARG_MASK = "mask"
        const val ARG_TIER = "tier"
    }
}
