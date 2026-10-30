package tv.own.owntv.lamassu

/** CPanel home banner slide (`GET /api/v1/banners` → `banners`). */
data class HomeSlide(
    val id: String,
    val imageUrl: String,
    val title: String? = null,
    val subtitle: String? = null,
    val actionKey: String? = null,
    val sortOrder: Int = 0,
)

/** CPanel home section card (`GET /api/v1/banners` → `sections`). */
data class HomeSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val actionKey: String? = null,
    val sortOrder: Int = 0,
    /** all | free | subscription */
    val audience: String = "all",
)
