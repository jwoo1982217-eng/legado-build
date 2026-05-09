package io.legado.app.help.source

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.Cookie
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.NetworkUtils

data class PrivateBookSourcePackage(
    val type: String = TYPE,
    val version: Int = 1,
    val sources: List<BookSource> = emptyList(),
    val cookies: List<Cookie> = emptyList(),
    val caches: List<Cache> = emptyList()
) {
    companion object {
        const val TYPE = "legado_private_book_source_package"
    }
}

object PrivateBookSourcePackager {

    fun create(sources: List<BookSource>): PrivateBookSourcePackage {
        val domains = sources.flatMap { sourceDomains(it) }.toSet()
        val cookies = domains.mapNotNull { domain ->
            val cookie = CookieStore.getCookie(domain)
            if (cookie.isBlank()) null else Cookie(domain, cookie)
        }
        val caches = sources
            .flatMap { appDb.cacheDao.getSourceVariables(it.bookSourceUrl) }
            .distinctBy { it.key }
        return PrivateBookSourcePackage(
            sources = sources,
            cookies = cookies,
            caches = caches
        )
    }

    fun restore(pkg: PrivateBookSourcePackage, selectedSources: List<BookSource>) {
        val sourceKeys = selectedSources.map { it.bookSourceUrl }.toSet()
        val domains = selectedSources.flatMap { sourceDomains(it) }.toSet()
        val cookies = pkg.cookies.filter { it.url in domains }
        val caches = pkg.caches.filter { cache ->
            sourceKeys.any { key -> cache.belongsToSource(key) }
        }
        if (cookies.isNotEmpty()) {
            appDb.cookieDao.insert(*cookies.toTypedArray())
        }
        if (caches.isNotEmpty()) {
            appDb.cacheDao.insert(*caches.toTypedArray())
        }
    }

    private fun Cache.belongsToSource(sourceKey: String): Boolean {
        return key == "userInfo_$sourceKey"
                || key == "loginHeader_$sourceKey"
                || key == "sourceVariable_$sourceKey"
                || key.startsWith("v_${sourceKey}_")
    }

    private fun sourceDomains(source: BookSource): Set<String> {
        return buildSet {
            listOf(
                source.bookSourceUrl,
                source.loginUrl,
                source.exploreUrl,
                source.searchUrl
            ).forEach { text ->
                urlsIn(text).forEach { url ->
                    runCatching {
                        NetworkUtils.getSubDomain(url)
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
        }
    }

    private fun urlsIn(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val direct = text.trim()
        val urls = Regex("""https?://[^\s"'<>，。；；]+""")
            .findAll(text)
            .map { it.value.trimEnd(',', ';', ')', ']', '}') }
            .toMutableList()
        if (direct.startsWith("http://", ignoreCase = true) ||
            direct.startsWith("https://", ignoreCase = true)
        ) {
            urls.add(direct)
        }
        return urls.distinct()
    }
}
