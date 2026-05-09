package io.legado.app.ui.book.source.manage

import android.app.Application
import android.text.TextUtils
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.toBookSource
import io.legado.app.help.source.PrivateBookSourcePackager
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.SourceAiAnalyzer
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.cnCompare
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.outputStream
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.writeToOutputStream
import splitties.init.appCtx
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * 书源管理数据修改
 * 修改数据要copy,直接修改会导致界面不刷新
 */
class BookSourceViewModel(application: Application) : BaseViewModel(application) {

    fun topSource(vararg sources: BookSourcePart) {
        execute {
            sources.sortBy { it.customOrder }
            val minOrder = appDb.bookSourceDao.minOrder - 1
            val array = sources.mapIndexed { index, it ->
                it.copy(customOrder = minOrder - index)
            }
            appDb.bookSourceDao.upOrder(array)
        }
    }

    fun bottomSource(vararg sources: BookSourcePart) {
        execute {
            sources.sortBy { it.customOrder }
            val maxOrder = appDb.bookSourceDao.maxOrder + 1
            val array = sources.mapIndexed { index, it ->
                it.copy(customOrder = maxOrder + index)
            }
            appDb.bookSourceDao.upOrder(array)
        }
    }

    fun del(sources: List<BookSourcePart>) {
        execute {
            SourceHelp.deleteBookSourceParts(sources)
        }
    }

    fun update(vararg bookSource: BookSource) {
        execute { appDb.bookSourceDao.update(*bookSource) }
    }

    fun upOrder(items: List<BookSourcePart>) {
        if (items.isEmpty()) return
        execute {
            appDb.bookSourceDao.upOrder(items)
        }
    }

    fun enable(enable: Boolean, items: List<BookSourcePart>) {
        execute {
            appDb.bookSourceDao.enable(enable, items)
        }
    }

    fun enableSelection(sources: List<BookSourcePart>) {
        execute {
            appDb.bookSourceDao.enable(true, sources)
        }
    }

    fun disableSelection(sources: List<BookSourcePart>) {
        execute {
            appDb.bookSourceDao.enable(false, sources)
        }
    }

    fun enableExplore(enable: Boolean, items: List<BookSourcePart>) {
        execute {
            appDb.bookSourceDao.enableExplore(enable, items)
        }
    }

    fun enableSelectExplore(sources: List<BookSourcePart>) {
        execute {
            appDb.bookSourceDao.enableExplore(true, sources)
        }
    }

    fun disableSelectExplore(sources: List<BookSourcePart>) {
        execute {
            appDb.bookSourceDao.enableExplore(false, sources)
        }
    }

    fun selectionAddToGroups(sources: List<BookSourcePart>, groups: String) {
        execute {
            val array = sources.map {
                it.copy().apply {
                    addGroup(groups)
                }
            }
            appDb.bookSourceDao.upGroup(array)
        }
    }

    fun selectionRemoveFromGroups(sources: List<BookSourcePart>, groups: String) {
        execute {
            val array = sources.map {
                it.copy().apply {
                    removeGroup(groups)
                }
            }
            appDb.bookSourceDao.upGroup(array)
        }
    }

    private fun saveToFile(
        sources: List<BookSource>,
        name: String,
        privatePackage: Boolean = false,
        success: (file: File, name: String) -> Unit
    ) {
        execute {
            val path = "${context.filesDir}/shareBookSource.json"
            FileUtils.delete(path)
            val file = FileUtils.createFileWithReplace(path)
            file.outputStream().buffered().use {
                if (privatePackage) {
                    GSON.writeToOutputStream(it, PrivateBookSourcePackager.create(sources))
                } else {
                    GSON.writeToOutputStream(it, sources)
                }
            }
            file
        }.onSuccess {
            success.invoke(it, name)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    fun saveToFile(
        adapter: BookSourceAdapter,
        searchKey: String?,
        sortAscending: Boolean,
        sort: BookSourceSort,
        success: (file: File, name: String) -> Unit
    ) {
        execute {
            val selection = adapter.selection
            val selectionSize = selection.size
            val sources = selectedSourcesForExport(adapter, searchKey, sortAscending, sort)
            val name = if (selectionSize == 1) {
                "bookSource_${selection.first().bookSourceName.normalizeFileName()}.json"
            } else {
                val timestamp =
                    java.text.SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date())
                "bookSource_$timestamp.json"
            }
            saveToFile(sources, name, success = success)
        }
    }

    fun savePrivateToFile(
        adapter: BookSourceAdapter,
        searchKey: String?,
        sortAscending: Boolean,
        sort: BookSourceSort,
        success: (file: File, name: String) -> Unit
    ) {
        execute {
            val sources = selectedSourcesForExport(adapter, searchKey, sortAscending, sort)
            val timestamp =
                java.text.SimpleDateFormat("yyyyMMddHHmm", Locale.getDefault()).format(Date())
            val name = if (sources.size == 1) {
                "privateBookSource_${sources.first().bookSourceName.normalizeFileName()}.json"
            } else {
                "privateBookSource_$timestamp.json"
            }
            saveToFile(sources, name, privatePackage = true, success = success)
        }
    }

    fun aiFilterInvalidSources(
        sources: List<BookSourcePart>,
        success: (message: String) -> Unit
    ) {
        execute {
            val bookSources = sources.toBookSource()
            if (bookSources.isEmpty()) {
                return@execute "没有选中的书源"
            }
            val candidates = bookSources.filter { source ->
                source.getInvalidGroupNames().isNotBlank()
                        || source.bookSourceComment.orEmpty().contains("// Error:")
                        || source.respondTime >= 180000L
            }
            if (candidates.isEmpty()) {
                bookSources.forEach { source ->
                    source.removeGroup("AI判定失效")
                    source.removeGroup("AI疑似失效")
                }
                appDb.bookSourceDao.update(*bookSources.toTypedArray())
                return@execute "没有发现需要 AI 判断的失效候选书源"
            }
            var usedFallback = false
            val decisions = kotlin.runCatching {
                candidates.chunked(40).flatMap { chunk ->
                    SourceAiAnalyzer.analyzeInvalidSources(chunk).getOrThrow()
                }
            }.getOrElse { error ->
                usedFallback = true
                SourceAiAnalyzer.localDecisions(candidates, "AI分析失败：${error.localizedMessage}")
            }.ifEmpty {
                usedFallback = true
                SourceAiAnalyzer.localDecisions(candidates, "AI没有返回有效筛选结果")
            }
            val decisionsByUrl = decisions.associateBy { it.url }
            var invalidCount = 0
            var suspectCount = 0
            bookSources.forEach { source ->
                source.removeGroup("AI判定失效")
                source.removeGroup("AI疑似失效")
                when (decisionsByUrl[source.bookSourceUrl]?.status) {
                    "invalid" -> {
                        source.addGroup("AI判定失效")
                        invalidCount++
                    }

                    "suspect" -> {
                        source.addGroup("AI疑似失效")
                        suspectCount++
                    }
                }
            }
            appDb.bookSourceDao.update(*bookSources.toTypedArray())
            val mode = if (usedFallback) "AI分析失败，已用本地校验兜底" else "AI分析完成"
            "$mode\nAI判定失效：$invalidCount 个\nAI疑似失效：$suspectCount 个"
        }.onSuccess {
            success.invoke(it)
        }.onError {
            context.toastOnUi(it.stackTraceStr)
        }
    }

    private fun selectedSourcesForExport(
        adapter: BookSourceAdapter,
        searchKey: String?,
        sortAscending: Boolean,
        sort: BookSourceSort
    ): List<BookSource> {
        val selection = adapter.selection
        if (selection.isEmpty()) {
            return emptyList()
        }
        val selectedRate = selection.size.toFloat() / adapter.itemCount.toFloat()
        return if (selectedRate == 1f) {
            getBookSources(searchKey, sortAscending, sort)
        } else if (selectedRate < 0.3) {
            selection.toBookSource()
        } else {
            val keys = selection.map { it.bookSourceUrl }.toHashSet()
            val bookSources = getBookSources(searchKey, sortAscending, sort)
            bookSources.filter {
                keys.contains(it.bookSourceUrl)
            }
        }
    }

    private fun getBookSources(
        searchKey: String?,
        sortAscending: Boolean,
        sort: BookSourceSort
    ): List<BookSource> {
        return when {
            searchKey.isNullOrEmpty() -> {
                appDb.bookSourceDao.all
            }

            searchKey == appCtx.getString(R.string.enabled) -> {
                appDb.bookSourceDao.allEnabled
            }

            searchKey == appCtx.getString(R.string.disabled) -> {
                appDb.bookSourceDao.allDisabled
            }

            searchKey == appCtx.getString(R.string.need_login) -> {
                appDb.bookSourceDao.allLogin
            }

            searchKey == appCtx.getString(R.string.no_group) -> {
                appDb.bookSourceDao.allNoGroup
            }

            searchKey == appCtx.getString(R.string.enabled_explore) -> {
                appDb.bookSourceDao.allEnabledExplore
            }

            searchKey == appCtx.getString(R.string.disabled_explore) -> {
                appDb.bookSourceDao.allDisabledExplore
            }

            searchKey.startsWith("group:") -> {
                val key = searchKey.substringAfter("group:")
                appDb.bookSourceDao.groupSearch(key)
            }

            else -> {
                appDb.bookSourceDao.search(searchKey)
            }
        }.let { data ->
            if (sortAscending) when (sort) {
                BookSourceSort.Weight -> data.sortedBy { it.weight }
                BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                    o1.bookSourceName.cnCompare(o2.bookSourceName)
                }

                BookSourceSort.Url -> data.sortedBy { it.bookSourceUrl }
                BookSourceSort.Update -> data.sortedByDescending { it.lastUpdateTime }
                BookSourceSort.Respond -> data.sortedBy { it.respondTime }
                BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                    var sortNum = -o1.enabled.compareTo(o2.enabled)
                    if (sortNum == 0) {
                        sortNum = o1.bookSourceName.cnCompare(o2.bookSourceName)
                    }
                    sortNum
                }

                else -> data
            }
            else when (sort) {
                BookSourceSort.Weight -> data.sortedByDescending { it.weight }
                BookSourceSort.Name -> data.sortedWith { o1, o2 ->
                    o2.bookSourceName.cnCompare(o1.bookSourceName)
                }

                BookSourceSort.Url -> data.sortedByDescending { it.bookSourceUrl }
                BookSourceSort.Update -> data.sortedBy { it.lastUpdateTime }
                BookSourceSort.Respond -> data.sortedByDescending { it.respondTime }
                BookSourceSort.Enable -> data.sortedWith { o1, o2 ->
                    var sortNum = o1.enabled.compareTo(o2.enabled)
                    if (sortNum == 0) {
                        sortNum = o1.bookSourceName.cnCompare(o2.bookSourceName)
                    }
                    sortNum
                }

                else -> data.reversed()
            }
        }
    }

    fun addGroup(group: String) {
        execute {
            val sources = appDb.bookSourceDao.noGroup
            sources.forEach { source ->
                source.bookSourceGroup = group
            }
            appDb.bookSourceDao.update(*sources.toTypedArray())
        }
    }

    fun upGroup(oldGroup: String, newGroup: String?) {
        execute {
            val sources = appDb.bookSourceDao.getByGroup(oldGroup)
            sources.forEach { source ->
                source.bookSourceGroup?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(oldGroup)
                    if (!newGroup.isNullOrEmpty())
                        it.add(newGroup)
                    source.bookSourceGroup = TextUtils.join(",", it)
                }
            }
            appDb.bookSourceDao.update(*sources.toTypedArray())
        }
    }

    fun delGroup(group: String) {
        execute {
            execute {
                val sources = appDb.bookSourceDao.getByGroup(group)
                sources.forEach { source ->
                    source.removeGroup(group)
                }
                appDb.bookSourceDao.update(*sources.toTypedArray())
            }
        }
    }

}
