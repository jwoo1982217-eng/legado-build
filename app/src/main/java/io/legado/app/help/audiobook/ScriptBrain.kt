package io.legado.app.help.audiobook

import android.content.Context
import android.os.Environment
import io.legado.app.model.ReadBook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ScriptBrain {

    data class ScriptCharacter(
        val name: String,
        val gender: String,
        val ageType: String,
        val voiceTag: String,
    )

    data class ScriptLine(
        val index: Int,
        val roleName: String,
        val voiceTag: String,
        val isNarration: Boolean,
        val text: String,
    )

    data class Analysis(
        val bookName: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val characters: List<ScriptCharacter>,
        val lines: List<ScriptLine>,
        val updatedAt: Long,
    )

    fun analyzeCurrentChapter(context: Context): Analysis {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter
        val bookName = book?.name?.ifBlank { "未命名书籍" } ?: "未命名书籍"
        val chapterIndex = chapter?.chapter?.index ?: ReadBook.durChapterIndex
        val chapterTitle = chapter?.let { it.title.ifBlank { it.chapter.title } } ?: "当前章"
        val text = chapter?.getNeedReadAloud(0, false, 0).orEmpty()
        val analysis = analyze(bookName, chapterIndex, chapterTitle, text)
        save(context.applicationContext, analysis)
        return analysis
    }

    private fun analyze(
        bookName: String,
        chapterIndex: Int,
        chapterTitle: String,
        chapterText: String,
    ): Analysis {
        val lines = buildList {
            val paragraphs = chapterText
                .replace("\r\n", "\n")
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }

            paragraphs.forEach { paragraph ->
                appendParagraph(paragraph, size)
            }
        }.mapIndexed { index, line ->
            line.copy(index = index + 1)
        }

        val characters = lines
            .asSequence()
            .filterNot { it.isNarration }
            .map { it.roleName }
            .filter { it.isNotBlank() && it != UNKNOWN_ROLE }
            .distinct()
            .map { inferCharacter(it) }
            .toList()

        return Analysis(
            bookName = bookName,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            characters = characters,
            lines = lines,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun MutableList<ScriptLine>.appendParagraph(paragraph: String, startIndex: Int) {
        var cursor = 0
        var lastSpeaker: String? = null
        quoteRegex.findAll(paragraph).forEach { match ->
            val before = paragraph.substring(cursor, match.range.first).trim()
            if (before.isNotBlank()) {
                add(narration(startIndex + size + 1, before))
                inferSpeaker(before)?.let { lastSpeaker = it }
            }
            val dialogue = match.groupValues[1].trim()
            if (dialogue.isNotBlank()) {
                val speaker = inferSpeaker(before) ?: lastSpeaker ?: UNKNOWN_ROLE
                add(dialogue(startIndex + size + 1, speaker, dialogue))
            }
            cursor = match.range.last + 1
        }
        val tail = paragraph.substring(cursor).trim()
        if (tail.isNotBlank()) {
            add(narration(startIndex + size + 1, tail))
        }
    }

    private fun narration(index: Int, text: String): ScriptLine {
        return ScriptLine(
            index = index,
            roleName = NARRATOR_ROLE,
            voiceTag = "旁白01",
            isNarration = true,
            text = text
        )
    }

    private fun dialogue(index: Int, speaker: String, text: String): ScriptLine {
        val character = inferCharacter(speaker)
        return ScriptLine(
            index = index,
            roleName = speaker,
            voiceTag = character.voiceTag,
            isNarration = false,
            text = text
        )
    }

    private fun inferSpeaker(beforeQuote: String): String? {
        if (beforeQuote.isBlank()) return null
        val normalized = beforeQuote
            .replace(Regex("[，。！？、；：\\s]+$"), "")
            .takeLast(24)
        speakerRegex.find(normalized)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isLikelySpeakerName()) return name
        }
        return null
    }

    private fun String.isLikelySpeakerName(): Boolean {
        if (length !in 1..8) return false
        return this !in pronouns
    }

    private fun inferCharacter(name: String): ScriptCharacter {
        if (name == UNKNOWN_ROLE) {
            return ScriptCharacter(name, "待定", "待定", "待分配")
        }
        val gender = when {
            femaleKeywords.any { name.contains(it) } -> "女"
            maleKeywords.any { name.contains(it) } -> "男"
            else -> "待定"
        }
        val ageType = when {
            childKeywords.any { name.contains(it) } -> "儿童"
            oldKeywords.any { name.contains(it) } -> "老年"
            else -> "青年"
        }
        val voiceTag = when {
            gender == "女" && ageType == "儿童" -> "女童01"
            gender == "男" && ageType == "儿童" -> "男童01"
            gender == "女" && ageType == "老年" -> "老年女01"
            gender == "男" && ageType == "老年" -> "老年男01"
            gender == "女" -> "女青年01"
            gender == "男" -> "男青年01"
            else -> "待分配"
        }
        return ScriptCharacter(name, gender, ageType, voiceTag)
    }

    private fun save(context: Context, analysis: Analysis) {
        val dir = scriptDir(context, analysis.bookName)
        dir.mkdirs()
        File(dir, "characters.json").writeText(analysis.characters.toJson().toString(2), Charsets.UTF_8)
        File(dir, "${analysis.chapterIndex}_${analysis.chapterTitle.safeFileName()}.json")
            .writeText(analysis.toJson().toString(2), Charsets.UTF_8)
    }

    private fun Analysis.toJson(): JSONObject {
        return JSONObject()
            .put("bookName", bookName)
            .put("chapterIndex", chapterIndex)
            .put("chapterTitle", chapterTitle)
            .put("updatedAt", updatedAt)
            .put("characters", characters.toJson())
            .put("lines", JSONArray().also { array ->
                lines.forEach { line ->
                    array.put(
                        JSONObject()
                            .put("index", line.index)
                            .put("roleName", line.roleName)
                            .put("voiceTag", line.voiceTag)
                            .put("isNarration", line.isNarration)
                            .put("text", line.text)
                    )
                }
            })
    }

    private fun List<ScriptCharacter>.toJson(): JSONArray {
        return JSONArray().also { array ->
            forEach { character ->
                array.put(
                    JSONObject()
                        .put("name", character.name)
                        .put("gender", character.gender)
                        .put("ageType", character.ageType)
                        .put("voiceTag", character.voiceTag)
                )
            }
        }
    }

    private fun scriptDir(context: Context, bookName: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(root, "script-brain/${bookName.safeFileName()}")
    }

    private fun String.safeFileName(): String {
        return replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").take(80).ifBlank { "未命名" }
    }

    private val quoteRegex = Regex("[“「『](.*?)[”」』]")
    private val speakerRegex = Regex("""([\u4e00-\u9fa5A-Za-z0-9·]{1,8})(?:轻声|低声|大声|冷冷|淡淡|笑着|皱眉|急忙|连忙|忍不住|忽然|沉声|柔声|小声)?(?:说道|说|问道|问|道|喊道|喊|叫道|叫|笑道|怒道|喝道|答道|回答|嘀咕|喃喃|开口)$""")
    private val pronouns = setOf("我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "众人", "男人", "女人", "少年", "少女")
    private val femaleKeywords = listOf("女", "娘", "妹", "姐", "姑", "婆", "姨", "母", "月", "雪", "柔", "婉")
    private val maleKeywords = listOf("男", "哥", "叔", "伯", "父", "爷", "掌柜", "凡", "轩", "辰", "峰")
    private val childKeywords = listOf("小", "童", "孩", "娃")
    private val oldKeywords = listOf("老", "婆", "爷", "伯", "叔", "掌柜", "嬷")
    private const val NARRATOR_ROLE = "旁白"
    private const val UNKNOWN_ROLE = "角色待定"
}
