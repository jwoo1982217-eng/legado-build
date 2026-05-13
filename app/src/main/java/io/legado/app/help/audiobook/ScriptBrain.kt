package io.legado.app.help.audiobook

import android.content.Context
import android.os.Environment
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadBook
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Wrapper
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
        val source: String = "本地规则",
        val error: String = "",
    )

    data class RuleRunResult(
        val analysis: Analysis,
        val rawQueueJson: String,
        val logs: List<String>,
        val moduleReports: List<ModuleRunReport> = emptyList(),
    )

    data class ModuleRunReport(
        val index: Int,
        val moduleId: String,
        val moduleName: String,
        val status: String,
        val message: String,
        val beforeTextLength: Int,
        val afterTextLength: Int,
        val beforeLineCount: Int,
        val afterLineCount: Int,
        val beforeCharacterCount: Int,
        val afterCharacterCount: Int,
        val sampleLines: List<String> = emptyList(),
    )

    private data class LockedAnalysisData(
        val characters: List<ScriptCharacter>,
        val lines: List<ScriptLine>,
    )

    private data class ModuleStats(
        val textLength: Int,
        val lineCount: Int,
        val characterCount: Int,
    )

    data class ImportedRuleInfo(
        val name: String,
        val author: String,
        val version: String,
        val isJson: Boolean,
        val rawLength: Int,
        val codeLength: Int,
    )

    data class SavedRule(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val updatedAt: Long,
        val isActive: Boolean,
    )

    data class RoleManagerSnapshot(
        val bookName: String,
        val pluginName: String,
        val pluginAuthor: String,
        val pluginVersion: String,
        val storagePath: String,
        val characters: List<ScriptCharacter>,
        val files: List<String>,
    )

    data class AnalysisModelProfile(
        val name: String,
        val provider: String = "",
        val modelUrl: String = "",
        val modelName: String = "",
        val modelKey: String = "",
    )

    data class AnalysisModule(
        val id: String,
        val name: String,
        val type: String = "js",
        val enabled: Boolean = true,
        val code: String = "",
    )

    fun analyzeCurrentChapter(context: Context): Analysis {
        return runAnalysisModulesForCurrentChapter(context.applicationContext).analysis
    }

    fun runAnalysisModulesForCurrentChapter(
        context: Context,
        stopAtModuleId: String? = null,
    ): RuleRunResult {
        val appContext = context.applicationContext
        val payload = currentChapterPayload()
        val modules = analysisModules(appContext)
        val enabledModules = modules.filter { it.enabled }
        val dir = scriptDir(appContext, payload.bookName).apply { mkdirs() }
        val existingCharacters = readRoleManagerCharacters(dir)
        val logs = arrayListOf(
            "分析中心：${payload.chapterTitle}",
            "整章正文：${payload.chapterText.length} 字"
        )
        var ctx = moduleContext(payload, existingCharacters)
        val reports = arrayListOf<ModuleRunReport>()
        for ((index, module) in modules.withIndex()) {
            val prefix = (index + 1).toString().padStart(2, '0')
            val before = moduleStats(ctx)
            if (!module.enabled) {
                logs.add("$prefix 跳过：${module.name}")
                reports.add(
                    ModuleRunReport(
                        index = index + 1,
                        moduleId = module.id,
                        moduleName = module.name,
                        status = "skipped",
                        message = "模块已关闭",
                        beforeTextLength = before.textLength,
                        afterTextLength = before.textLength,
                        beforeLineCount = before.lineCount,
                        afterLineCount = before.lineCount,
                        beforeCharacterCount = before.characterCount,
                        afterCharacterCount = before.characterCount,
                        sampleLines = moduleSampleLines(ctx),
                    )
                )
                if (module.id == stopAtModuleId) break
                continue
            }
            logs.add("$prefix 运行：${module.name}")
            runCatching {
                ctx = runAnalysisModule(appContext, module, ctx, logs)
                val after = moduleStats(ctx)
                reports.add(
                    ModuleRunReport(
                        index = index + 1,
                        moduleId = module.id,
                        moduleName = module.name,
                        status = "ok",
                        message = "运行成功",
                        beforeTextLength = before.textLength,
                        afterTextLength = after.textLength,
                        beforeLineCount = before.lineCount,
                        afterLineCount = after.lineCount,
                        beforeCharacterCount = before.characterCount,
                        afterCharacterCount = after.characterCount,
                        sampleLines = moduleSampleLines(ctx),
                    )
                )
            }.onFailure { error ->
                val message = error.localizedMessage ?: error.javaClass.simpleName
                logs.add("$prefix 失败：${module.name} - $message")
                reports.add(
                    ModuleRunReport(
                        index = index + 1,
                        moduleId = module.id,
                        moduleName = module.name,
                        status = "failed",
                        message = message,
                        beforeTextLength = before.textLength,
                        afterTextLength = before.textLength,
                        beforeLineCount = before.lineCount,
                        afterLineCount = before.lineCount,
                        beforeCharacterCount = before.characterCount,
                        afterCharacterCount = before.characterCount,
                        sampleLines = moduleSampleLines(ctx),
                    )
                )
            }
            if (module.id == stopAtModuleId) break
        }
        val ctxLogs = ctx.optJSONArray("logs").toStringList()
        val analysis = analysisFromModuleContext(
            payload = payload,
            ctx = ctx,
            existingCharacters = existingCharacters,
        ).copy(
            source = "内置分析模块",
            error = if (enabledModules.isEmpty()) "没有启用的分析模块，已用本地基础规则生成台词本。" else "",
        )
        save(appContext, analysis)
        val rawQueueJson = analysis.toQueueJson().toString(2)
        File(dir, "last_audio_queue.json").writeText(rawQueueJson, Charsets.UTF_8)
        val finalLogs = (logs + ctxLogs).distinct()
        File(dir, "last_module_log.txt").writeText(finalLogs.joinToString("\n"), Charsets.UTF_8)
        return RuleRunResult(analysis, rawQueueJson, finalLogs, reports)
    }

    fun runImportedRuleForCurrentChapter(context: Context): RuleRunResult {
        val rule = loadImportedRule(context)
        if (rule.isBlank()) error("还没有导入朗读规则")
        return runImportedRule(
            context = context.applicationContext,
            payload = currentChapterPayload(),
            ruleText = rule,
        )
    }

    fun saveImportedRule(context: Context, raw: String, alsoSaveToLibrary: Boolean = true) {
        val normalized = normalizeImportedRule(raw)
        parseAnalysisModules(normalized).takeIf { it.isNotEmpty() }?.let {
            saveAnalysisModules(context.applicationContext, it)
        }
        val code = extractRuleCode(normalized)
        globalDir(context.applicationContext).mkdirs()
        importedRuleJsonFile(context.applicationContext).writeText(normalized, Charsets.UTF_8)
        if (alsoSaveToLibrary) {
            saveRuleToLibrary(context.applicationContext, normalized)
        }
        if (code.isBlank()) {
            importedRuleFile(context.applicationContext).delete()
            return
        }
        importedRuleFile(context.applicationContext).writeText(code, Charsets.UTF_8)
    }

    fun loadImportedRule(context: Context): String {
        val file = importedRuleFile(context.applicationContext)
        return file.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
    }

    fun loadImportedRuleRaw(context: Context): String {
        val jsonFile = importedRuleJsonFile(context.applicationContext)
        if (jsonFile.exists()) return jsonFile.readText(Charsets.UTF_8)
        return loadImportedRule(context)
    }

    fun importedRuleInfo(context: Context): ImportedRuleInfo? {
        val raw = loadImportedRuleRaw(context).trim()
        if (raw.isBlank()) return null
        return ruleInfoFromRaw(raw)
    }

    fun savedRules(context: Context): List<SavedRule> {
        val activeRaw = loadImportedRuleRaw(context.applicationContext).trim()
        val dir = ruleLibraryDir(context.applicationContext)
        return dir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { file ->
                val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.trim().orEmpty()
                if (raw.isBlank()) return@mapNotNull null
                val info = ruleInfoFromRaw(raw)
                SavedRule(
                    id = file.nameWithoutExtension,
                    name = info.name,
                    author = info.author,
                    version = info.version,
                    updatedAt = file.lastModified(),
                    isActive = raw == activeRaw,
                )
            }
            ?.sortedWith(compareByDescending<SavedRule> { it.isActive }.thenBy { it.name })
            .orEmpty()
    }

    fun useSavedRule(context: Context, id: String) {
        val file = File(ruleLibraryDir(context.applicationContext), "${id.safeFileName()}.json")
        require(file.exists()) { "规则不存在" }
        saveImportedRule(context.applicationContext, file.readText(Charsets.UTF_8), alsoSaveToLibrary = false)
    }

    fun analysisModules(context: Context): List<AnalysisModule> {
        val raw = context.applicationContext.getPrefString(KEY_ANALYSIS_MODULES).orEmpty()
        val modules = parseAnalysisModules(raw).ifEmpty { defaultAnalysisModules() }
        val savedVersion = context.applicationContext
            .getPrefString(KEY_ANALYSIS_MODULES_VERSION)
            .orEmpty()
            .toIntOrNull() ?: 0
        if (savedVersion >= DEFAULT_ANALYSIS_MODULE_VERSION) return modules

        val defaults = defaultAnalysisModules().associateBy { it.id }
        val upgraded = modules.map { module ->
            defaults[module.id]?.let { latest ->
                module.copy(
                    name = latest.name,
                    type = latest.type,
                    code = latest.code,
                )
            } ?: module
        }.ifEmpty { defaultAnalysisModules() }
        saveAnalysisModules(context.applicationContext, upgraded)
        context.applicationContext.putPrefString(
            KEY_ANALYSIS_MODULES_VERSION,
            DEFAULT_ANALYSIS_MODULE_VERSION.toString()
        )
        return upgraded
    }

    fun saveAnalysisModules(context: Context, modules: List<AnalysisModule>) {
        val array = JSONArray().also { target ->
            modules.forEach { target.put(it.toJson()) }
        }
        context.applicationContext.putPrefString(KEY_ANALYSIS_MODULES, array.toString())
    }

    fun setAnalysisModuleEnabled(context: Context, id: String, enabled: Boolean) {
        saveAnalysisModules(
            context.applicationContext,
            analysisModules(context.applicationContext).map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
        )
    }

    fun upsertAnalysisModule(context: Context, module: AnalysisModule) {
        val modules = analysisModules(context.applicationContext).toMutableList()
        val index = modules.indexOfFirst { it.id == module.id }
        if (index >= 0) {
            modules[index] = module
        } else {
            modules.add(module)
        }
        saveAnalysisModules(context.applicationContext, modules)
    }

    fun deleteAnalysisModule(context: Context, id: String) {
        saveAnalysisModules(
            context.applicationContext,
            analysisModules(context.applicationContext).filterNot { it.id == id }
        )
    }

    fun moveAnalysisModule(context: Context, id: String, delta: Int) {
        val modules = analysisModules(context.applicationContext).toMutableList()
        val index = modules.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, modules.lastIndex)
        if (target == index) return
        val item = modules.removeAt(index)
        modules.add(target, item)
        saveAnalysisModules(context.applicationContext, modules)
    }

    fun exportAnalysisRulePackage(context: Context): String {
        val info = importedRuleInfo(context.applicationContext)
        val rawRule = loadImportedRuleRaw(context.applicationContext)
        return JSONObject()
            .put("name", info?.name ?: "默认多角色分析规则")
            .put("type", "script_brain_module_rule")
            .put("version", info?.version ?: "1")
            .put("author", info?.author ?: "阅读端内置")
            .put("modules", analysisModules(context.applicationContext).toJsonArray())
            .put("speechRule", rawRule.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .toString(2)
    }

    fun defaultModuleCode(id: String, name: String): String {
        val purpose = when (id) {
            "text_preprocess" -> "整理章节段落、换行和空白，必须保留原文。"
            "ad_clean_regex" -> "用本地正则清理疑似广告、下载提示和推广段落。"
            "dialogue_split" -> "识别旁白、引号对话、半句对话。"
            "speaker_resolve" -> "根据上下文判断每句对话属于哪个角色。"
            "alias_merge" -> "合并角色名、称呼、代词和别名。"
            "voice_tag" -> "为角色分配男/男青年01、女/女青年01等唯一音色标签。"
            "emotion" -> "为台词标注平静、紧张、愤怒、哽咽等情绪。"
            "validate" -> "检查未知角色、空台词、音色复用和异常归属。"
            else -> "处理台词本或角色表，返回更新后的 ctx。"
        }
        when (id) {
            "text_preprocess" -> return """
                function run(ctx) {
                  ctx.logs = ctx.logs || [];
                  ctx.chapterText = String(ctx.chapterText || "")
                    .replace(/\r\n/g, "\n")
                    .replace(/[ \t]+\n/g, "\n")
                    .replace(/\n{3,}/g, "\n\n")
                    .trim();
                  ctx.logs.push("文本预处理完成：" + ctx.chapterText.length + " 字");
                  return ctx;
                }
            """.trimIndent()

            "ad_clean_regex" -> return """
                function run(ctx) {
                  ctx.logs = ctx.logs || [];
                  var text = String(ctx.chapterText || "");
                  var before = text.length;
                  var rules = [
                    /请收藏本站.*$/gm,
                    /最新网址.*$/gm,
                    /本章未完.*$/gm,
                    /喜欢本书请.*$/gm,
                    /手机用户请.*$/gm,
                    /app下载.*$/gm,
                    /加入书签.*$/gm,
                    /推荐票.*月票.*$/gm,
                    /.*访问.*最新地址.*$/gm
                  ];
                  for (var i = 0; i < rules.length; i++) {
                    text = text.replace(rules[i], "");
                  }
                  ctx.chapterText = text.replace(/\n{3,}/g, "\n\n").trim();
                  ctx.logs.push("本地正则清理完成：移除约 " + Math.max(0, before - ctx.chapterText.length) + " 字");
                  return ctx;
                }
            """.trimIndent()

            "dialogue_split" -> return """
                function run(ctx) {
                  ctx.logs = ctx.logs || [];
                  var text = String(ctx.chapterText || "");
                  var paragraphs = text.split(/\n+/).map(function(p) { return p.trim(); }).filter(Boolean);
                  var lines = [];
                  function speakerFrom(text) {
                    var m = String(text || "").match(/([\u4e00-\u9fa5A-Za-z0-9·]{1,12})(?:轻声|低声|沉声|冷声)?(?:说|问|道|喊|叫|答|笑道|骂道|喝道|说道)/);
                    return m ? m[1] : "";
                  }
                  function addLine(roleName, isNarration, content) {
                    content = String(content || "").trim();
                    if (!content) return;
                    lines.push({
                      index: lines.length + 1,
                      roleName: roleName || "旁白",
                      voiceTag: isNarration ? "旁白" : "待分配",
                      isNarration: !!isNarration,
                      tag: isNarration ? "narration" : (roleName || "未知角色"),
                      text: content
                    });
                  }
                  paragraphs.forEach(function(p) {
                    var cursor = 0;
                    var re = /[“"「『](.*?)[”"」』]/g;
                    var match;
                    var lastSpeaker = speakerFrom(p);
                    while ((match = re.exec(p)) !== null) {
                      var before = p.substring(cursor, match.index).trim();
                      if (before) addLine("旁白", true, before);
                      addLine(lastSpeaker || "未知角色", false, match[1]);
                      cursor = match.index + match[0].length;
                    }
                    var tail = p.substring(cursor).trim();
                    if (tail) addLine("旁白", true, tail);
                  });
                  ctx.lines = lines;
                  ctx.logs.push("对话切分完成：" + lines.length + " 行");
                  return ctx;
                }
            """.trimIndent()

            "speaker_resolve" -> return """
                function run(ctx) {
                  ctx.logs = ctx.logs || [];
                  var BAD = {
                    "我":1,"你":1,"他":1,"她":1,"它":1,"我们":1,"你们":1,"他们":1,"她们":1,
                    "众人":1,"有人":1,"一人":1,"男人":1,"女人":1,"少年":1,"少女":1,
                    "旁白":1,"未知":1,"未知角色":1,"未知发言人":1
                  };
                  function cleanName(name) {
                    name = String(name || "").trim();
                    name = name.replace(/[“”"「」『』《》（）()【】\\[\\]{}]/g, "");
                    name = name.replace(/^(那个|这个|那名|这名|一名|一个|一位|那位|这位)/, "");
                    name = name.replace(/(轻声|低声|沉声|冷声|柔声|小声|大声|笑着|皱眉|急忙|连忙|忍不住|忽然|淡淡|冷冷|怒声|厉声|颤声|哑声|平静地|认真地|疑惑地|无奈地|说道|说|问道|问|道|喊道|喊|叫道|叫|笑道|怒道|喝道|答道|回答|开口|嘀咕|喃喃).*$/g, "");
                    name = name.replace(/^[，。！？、；：,.!?;:\\s]+|[，。！？、；：,.!?;:\\s]+$/g, "");
                    if (name.length > 8) name = "";
                    if (BAD[name]) name = "";
                    return name;
                  }
                  function inferLocal(text) {
                    text = String(text || "");
                    var patterns = [
                      /([\u4e00-\u9fa5A-Za-z0-9·]{1,8})(?:轻声|低声|沉声|冷声|柔声|小声|大声|笑着|皱眉|急忙|连忙|忍不住|忽然|淡淡|冷冷|怒声|厉声|颤声|哑声|平静地|认真地|疑惑地|无奈地)?(?:说道|说|问道|问|道|喊道|喊|叫道|叫|笑道|怒道|喝道|答道|回答|开口|嘀咕|喃喃)/,
                      /(?:说道|说|问道|问|道|喊道|喊|叫道|叫|笑道|怒道|喝道|答道|回答|开口|嘀咕|喃喃)(?:的|的人)?是([\u4e00-\u9fa5A-Za-z0-9·]{1,8})/,
                      /([\u4e00-\u9fa5A-Za-z0-9·]{1,8})(?:看向|望向|瞪着|盯着|拉住|拦住).{0,16}(?:说道|说|问|道|喊|叫)/
                    ];
                    for (var i = 0; i < patterns.length; i++) {
                      var m = text.match(patterns[i]);
                      var n = m ? cleanName(m[1]) : "";
                      if (n) return n;
                    }
                    return "";
                  }
                  function lineBrief(line) {
                    return {
                      index: line.index,
                      text: String(line.text || "").slice(0, 180),
                      isNarration: !!line.isNarration,
                      currentRoleName: line.roleName || ""
                    };
                  }
                  function applyAiResult(result) {
                    var items = result && (result.items || result.lines || result.audioQueue || result);
                    if (!items || !items.length) return 0;
                    var byIndex = {};
                    for (var i = 0; i < items.length; i++) {
                      var it = items[i] || {};
                      var idx = parseInt(it.index || it.id || it.seq, 10);
                      if (!isNaN(idx)) byIndex[idx] = it;
                    }
                    var changed = 0;
                    (ctx.lines || []).forEach(function(line) {
                      var it = byIndex[line.index];
                      if (!it || line.isNarration) return;
                      var name = cleanName(it.roleName || it.speaker || it.character || it.name);
                      if (!name) return;
                      line.roleName = name;
                      line.tag = name;
                      if (it.gender) line.gender = String(it.gender);
                      if (it.ageType || it.age) line.ageType = String(it.ageType || it.age);
                      if (it.emotion) line.emotion = String(it.emotion);
                      changed++;
                    });
                    return changed;
                  }
                  var localLastSpeaker = "";
                  (ctx.lines || []).forEach(function(line, idx) {
                    if (line.isNarration || line.roleName === "旁白") {
                      var local = inferLocal(line.text);
                      if (local) localLastSpeaker = local;
                    } else if (!line.roleName || line.roleName === "未知角色") {
                      var prev = idx > 0 ? ctx.lines[idx - 1] : null;
                      var next = idx + 1 < ctx.lines.length ? ctx.lines[idx + 1] : null;
                      var guess = "";
                      if (prev) guess = inferLocal(prev.text);
                      if (!guess && next) guess = inferLocal(next.text);
                      line.roleName = guess || localLastSpeaker || "未知角色";
                      line.tag = line.roleName;
                    } else {
                      var clean = cleanName(line.roleName);
                      if (clean) {
                        line.roleName = clean;
                        line.tag = clean;
                        localLastSpeaker = clean;
                      }
                    }
                  });
                  var aiChanged = 0;
                  if (typeof brain !== "undefined" && brain && brain.hasModel && brain.hasModel()) {
                    try {
                      var dialogueLines = (ctx.lines || []).filter(function(line) { return !line.isNarration; }).map(lineBrief);
                      var prompt =
                        "你正在为小说有声书做多角色朗读分析。请根据整章上下文判断每一句对话的说话人。\\n" +
                        "规则：1. 不要把引号内部提到的人名当说话人；2. 旁白保持旁白；3. 角色名必须清洗成真实人物名，不要带说/问/动作词；4. 不确定时给出最合理的临时称呼，如群众男青年、群众女青年；5. 输出 JSON。\\n" +
                        "输出格式：{\"items\":[{\"index\":2,\"roleName\":\"张三\",\"gender\":\"男\",\"ageType\":\"男/男青年\",\"emotion\":\"平静\"}]}\\n" +
                        "书名：" + ctx.bookName + "\\n章节：" + ctx.chapterTitle + "\\n" +
                        "已有角色表：" + JSON.stringify(ctx.characters || []) + "\\n" +
                        "整章正文：\\n" + String(ctx.chapterText || "").slice(0, 12000) + "\\n" +
                        "待归属台词：\\n" + JSON.stringify(dialogueLines);
                      var aiText = brain.chatJson(prompt);
                      aiChanged = applyAiResult(JSON.parse(aiText));
                      ctx.logs.push("AI说话人归属完成：更新 " + aiChanged + " 行");
                    } catch (e) {
                      ctx.logs.push("AI说话人归属失败，已保留本地归属：" + e);
                    }
                  }
                  ctx.logs.push("说话人归属完成：AI更新 " + aiChanged + " 行");
                  return ctx;
                }
            """.trimIndent()

            "alias_merge" -> return """
                function run(ctx) {
                  ctx.logs = ctx.logs || [];
                  function cleanName(name) {
                    name = String(name || "").trim();
                    name = name.replace(/[“”"「」『』《》（）()【】\\[\\]{}]/g, "");
                    name = name.replace(/^(那个|这个|那名|这名|一名|一个|一位|那位|这位)/, "");
                    name = name.replace(/(轻声|低声|沉声|冷声|柔声|小声|大声|笑着|皱眉|急忙|连忙|忍不住|忽然|淡淡|冷冷|怒声|厉声|颤声|哑声|平静地|认真地|疑惑地|无奈地|说道|说|问道|问|道|喊道|喊|叫道|叫|笑道|怒道|喝道|答道|回答|开口|嘀咕|喃喃).*$/g, "");
                    name = name.replace(/^[，。！？、；：,.!?;:\\s]+|[，。！？、；：,.!?;:\\s]+$/g, "");
                    if (/^(我|你|他|她|它|我们|你们|他们|她们|有人|众人|一人|旁白|未知|未知角色)$/.test(name)) return "";
                    if (name.length > 8) return "";
                    return name;
                  }
                  function genderOf(name) {
                    if (/她|娘|姐|妹|女|月|雪|花|香|玉|兰|柔|晴|薇|妍|媛|婉|姝|瑶|嫣/.test(name)) return "女";
                    if (/爷|叔|伯|哥|男|夫|郎|公|王|将|侯|掌柜|父|兄/.test(name)) return "男";
                    return "";
                  }
                  function ageOf(name, gender) {
                    if (/老|婆|嬷|爷|伯|叔|掌柜|太君|夫人|长老/.test(name)) return (gender === "女" ? "女/女老年" : "男/男老年");
                    if (/小|童|孩|丫|娃/.test(name)) return (gender === "女" ? "女/女童" : "男/男童");
                    if (/少年|少爷|公子/.test(name)) return "男/少年";
                    if (/少女|姑娘|小姐/.test(name)) return "女/少女";
                    return gender === "女" ? "女/女青年" : "男/男青年";
                  }
                  var aliasMap = {};
                  (ctx.characters || []).forEach(function(ch) {
                    var main = cleanName(ch.name);
                    if (!main) return;
                    aliasMap[main] = main;
                    String(ch.aliases || ch.name || "").split("|").forEach(function(alias) {
                      alias = cleanName(alias);
                      if (alias) aliasMap[alias] = main;
                    });
                  });
                  var byName = {};
                  var changed = 0;
                  (ctx.lines || []).forEach(function(line) {
                    if (line.isNarration || line.roleName === "旁白") return;
                    var clean = cleanName(line.roleName);
                    if (!clean) {
                      line.roleName = "未知角色";
                      line.tag = "未知角色";
                      return;
                    }
                    var main = aliasMap[clean] || clean;
                    if (main !== line.roleName) changed++;
                    line.roleName = main;
                    line.tag = main;
                    if (!byName[main]) {
                      var gender = line.gender || genderOf(main) || "男";
                      byName[main] = {
                        name: main,
                        aliases: main === clean ? [main] : [main, clean],
                        gender: gender,
                        ageType: line.ageType || ageOf(main, gender),
                        voiceTag: line.voiceTag || "待分配"
                      };
                    } else if (clean !== main && byName[main].aliases.indexOf(clean) < 0) {
                      byName[main].aliases.push(clean);
                    }
                  });
                  var characters = [];
                  for (var name in byName) {
                    if (byName.hasOwnProperty(name)) characters.push(byName[name]);
                  }
                  ctx.characters = characters;
                  ctx.logs.push("角色名清洗/别名合并完成：角色 " + characters.length + " 个，修正 " + changed + " 行");
                  return ctx;
                }
            """.trimIndent()

            "voice_tag" -> return """
                function run(ctx) {
                  ctx.logs = ctx.logs || [];
                  var used = {};
                  var characters = [];
                  function genderOf(name) {
                    if (/她|娘|姐|妹|女|月|雪|花|香|玉|兰|柔|晴/.test(name)) return "女";
                    if (/爷|叔|伯|哥|男|夫|郎|公|王|将|侯/.test(name)) return "男";
                    return "男";
                  }
                  function ageOf(name, gender) {
                    if (/老|婆|嬷|爷|伯|叔|掌柜/.test(name)) return gender + "/" + gender + "老年";
                    if (/小|童|孩|丫/.test(name)) return gender + "/" + (gender === "女" ? "女童" : "男童");
                    return gender + "/" + gender + "青年";
                  }
                  function nextVoice(age) {
                    used[age] = (used[age] || 0) + 1;
                    var num = String(used[age]);
                    if (num.length < 2) num = "0" + num;
                    return age + num;
                  }
                  var byName = {};
                  (ctx.lines || []).forEach(function(line) {
                    if (line.isNarration || line.roleName === "旁白" || line.roleName === "未知角色") return;
                    if (!byName[line.roleName]) {
                      var gender = genderOf(line.roleName);
                      var age = ageOf(line.roleName, gender);
                      byName[line.roleName] = {
                        name: line.roleName,
                        aliases: [line.roleName],
                        gender: gender,
                        ageType: age,
                        voiceTag: nextVoice(age)
                      };
                      characters.push(byName[line.roleName]);
                    }
                    line.voiceTag = byName[line.roleName].voiceTag;
                  });
                  ctx.characters = characters;
                  ctx.logs.push("音色标签分配完成：" + characters.length + " 个角色");
                  return ctx;
                }
            """.trimIndent()

            "emotion" -> return """
                function run(ctx) {
                  ctx.logs = ctx.logs || [];
                  (ctx.lines || []).forEach(function(line) {
                    var text = String(line.text || "");
                    if (/怒|吼|骂|喝|咆哮/.test(text)) line.emotion = "愤怒";
                    else if (/哭|泪|哽咽|难过|悲/.test(text)) line.emotion = "悲伤";
                    else if (/笑|哈哈|轻松/.test(text)) line.emotion = "轻松";
                    else if (/怕|惊|慌|颤/.test(text)) line.emotion = "紧张";
                    else line.emotion = "平静";
                  });
                  ctx.logs.push("情绪分析完成");
                  return ctx;
                }
            """.trimIndent()

            "validate" -> return """
                function run(ctx) {
                  ctx.logs = ctx.logs || [];
                  ctx.lines = (ctx.lines || []).filter(function(line) {
                    return String(line.text || "").trim().length > 0;
                  }).map(function(line, index) {
                    line.index = index + 1;
                    if (!line.roleName) line.roleName = "旁白";
                    if (line.roleName === "旁白") {
                      line.isNarration = true;
                      line.voiceTag = "旁白";
                      line.tag = "narration";
                    }
                    return line;
                  });
                  ctx.logs.push("结果校验完成：" + ctx.lines.length + " 行");
                  return ctx;
                }
            """.trimIndent()
        }
        return """
            /**
             * 模块：$name
             * 作用：$purpose
             *
             * ctx 可用字段：
             * - ctx.bookName / ctx.bookUrl
             * - ctx.chapterIndex / ctx.chapterTitle / ctx.chapterText
             * - ctx.lines: [{ index, roleName, voiceTag, isNarration, text, emotion }]
             * - ctx.characters: [{ name, aliases, gender, ageType, voiceTag }]
             * - ctx.logs: []
             *
             * 返回：
             * - return ctx
             * - 或 return { lines: ctx.lines, characters: ctx.characters, logs: ctx.logs }
             */
            function run(ctx) {
              ctx.logs = ctx.logs || [];
              ctx.lines = ctx.lines || [];
              ctx.characters = ctx.characters || [];
              ctx.logs.push("$name 完成");
              return ctx;
            }
        """.trimIndent()
    }

    fun modelProfiles(context: Context): List<AnalysisModelProfile> {
        val json = context.applicationContext.getPrefString(KEY_MODEL_PROFILES).orEmpty()
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val profile = AnalysisModelProfile(
                    name = firstText(item, "name"),
                    provider = firstText(item, "provider"),
                    modelUrl = firstText(item, "modelUrl", "baseUrl", "url"),
                    modelName = firstText(item, "modelName", "model"),
                    modelKey = firstText(item, "modelKey", "apiKey", "key"),
                )
                if (profile.name.isNotBlank() && profile.modelUrl.isNotBlank() && profile.modelName.isNotBlank()) {
                    add(profile)
                }
            }
        }.distinctBy { it.name }
    }

    fun saveModelProfiles(context: Context, profiles: List<AnalysisModelProfile>) {
        val normalized = profiles
            .map {
                it.copy(
                    name = it.name.trim(),
                    provider = it.provider.trim(),
                    modelUrl = it.modelUrl.trim(),
                    modelName = it.modelName.trim(),
                    modelKey = it.modelKey.trim(),
                )
            }
            .filter { it.name.isNotBlank() && it.modelUrl.isNotBlank() && it.modelName.isNotBlank() }
            .distinctBy { it.name }
        val array = JSONArray().also { target ->
            normalized.forEach { target.put(it.toScriptModelJson()) }
        }
        context.applicationContext.putPrefString(KEY_MODEL_PROFILES, array.toString())
        val selected = selectedModelProfileName(context.applicationContext)
        if (selected.isNotBlank() && normalized.none { it.name == selected }) {
            saveSelectedModelProfileName(context.applicationContext, normalized.firstOrNull()?.name.orEmpty())
        }
    }

    fun selectedModelProfileName(context: Context): String {
        return context.applicationContext.getPrefString(KEY_SELECTED_MODEL_PROFILE).orEmpty()
    }

    fun saveSelectedModelProfileName(context: Context, name: String) {
        context.applicationContext.putPrefString(KEY_SELECTED_MODEL_PROFILE, name)
    }

    fun selectedModelProfileNames(context: Context): Set<String> {
        return context.applicationContext
            .getPrefStringSet(KEY_SELECTED_MODEL_PROFILES, mutableSetOf())
            ?.toSet()
            .orEmpty()
    }

    fun saveSelectedModelProfileNames(context: Context, names: Set<String>) {
        context.applicationContext.putPrefStringSet(
            KEY_SELECTED_MODEL_PROFILES,
            names.filter { it.isNotBlank() }.toMutableSet()
        )
    }

    fun selectModelProfile(context: Context, profile: AnalysisModelProfile) {
        saveSelectedModelProfileName(context.applicationContext, profile.name)
        saveSelectedModelProfileNames(context.applicationContext, setOf(profile.name))
    }

    fun selectedModelProfile(context: Context): AnalysisModelProfile? {
        return modelProfiles(context.applicationContext)
            .firstOrNull { it.name == selectedModelProfileName(context.applicationContext) }
    }

    fun selectedModelProfiles(context: Context): List<AnalysisModelProfile> {
        val profiles = modelProfiles(context.applicationContext)
        if (profiles.isEmpty()) return emptyList()
        val selectedNames = selectedModelProfileNames(context.applicationContext)
        val selected = profiles.filter { it.name in selectedNames }
        if (selected.isNotEmpty()) return selected
        return selectedModelProfile(context.applicationContext)?.let { listOf(it) } ?: profiles.take(1)
    }

    fun testModel(profile: AnalysisModelProfile): Result<String> = runCatching {
        val url = profile.modelUrl.trim()
        val model = profile.modelName.trim()
        require(url.isNotBlank()) { "请先填写模型地址" }
        require(model.isNotBlank()) { "请先填写模型名" }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
            )
            .put("temperature", 0)
            .toString()
        val request = Request.Builder()
            .url(normalizeChatCompletionsUrl(url))
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .header("Content-Type", "application/json")
            .apply {
                if (profile.modelKey.isNotBlank()) {
                    header("Authorization", "Bearer ${profile.modelKey.trim()}")
                }
            }
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${text.take(240)}")
            }
            text
        }
    }

    fun builtInRoleManagerInfo(context: Context): ImportedRuleInfo? {
        val raw = runCatching {
            context.applicationContext.assets.open(ROLE_MANAGER_PLUGIN_ASSET).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        }.getOrNull()?.trim().orEmpty()
        if (raw.isBlank()) return null
        val json = runCatching {
            if (raw.startsWith("[")) JSONArray(raw).optJSONObject(0) else JSONObject(raw)
        }.getOrNull()
        val code = firstText(json, "code", "js", "script", "content", "source")
        return ImportedRuleInfo(
            name = firstText(json, "name", "ruleName", "title", "displayName")
                .ifBlank { "角色管理插件" },
            author = firstText(json, "author", "creator", "user").ifBlank { "未知" },
            version = firstText(json, "version", "versionName", "updateTime").ifBlank { "未标注" },
            isJson = json != null,
            rawLength = raw.length,
            codeLength = code.length,
        )
    }

    fun roleManagerSnapshot(context: Context): RoleManagerSnapshot {
        val analysis = analyzeCurrentChapter(context.applicationContext)
        val dir = scriptDir(context.applicationContext, analysis.bookName).apply { mkdirs() }
        val characters = readRoleManagerCharacters(dir).ifEmpty { analysis.characters }
        val info = builtInRoleManagerInfo(context.applicationContext)
        return RoleManagerSnapshot(
            bookName = analysis.bookName,
            pluginName = info?.name ?: "角色管理插件",
            pluginAuthor = info?.author ?: "未知",
            pluginVersion = info?.version ?: "未标注",
            storagePath = dir.absolutePath,
            characters = characters,
            files = ROLE_MANAGER_FILES
                .map { it.replace("<book>", analysis.bookName.safeFileName()) }
                .filter { File(dir, it).exists() }
        )
    }

    fun clearImportedRule(context: Context) {
        importedRuleFile(context.applicationContext).delete()
        importedRuleJsonFile(context.applicationContext).delete()
    }

    fun hasImportedRule(context: Context): Boolean {
        return loadImportedRule(context).isNotBlank()
    }

    private fun currentChapterPayload(): ChapterPayload {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter
        val chapterText = if (book != null && chapter != null) {
            runCatching {
                BookHelp.getContent(book, chapter.chapter)
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        ContentProcessor.get(book)
                            .getContent(
                                book = book,
                                chapter = chapter.chapter,
                                content = it,
                                includeTitle = false
                            )
                            .toString()
                            .trim()
                    }
            }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: chapter.getContent().trim().takeIf { it.isNotBlank() }
                ?: chapter.getNeedReadAloud(0, false, 0).trim()
        } else {
            ""
        }
        return ChapterPayload(
            bookName = book?.name?.ifBlank { "未命名书籍" } ?: "未命名书籍",
            bookUrl = book?.bookUrl.orEmpty(),
            chapterIndex = chapter?.chapter?.index ?: ReadBook.durChapterIndex,
            chapterTitle = chapter?.let { it.title.ifBlank { it.chapter.title } } ?: "当前章",
            chapterText = chapterText,
        )
    }

    private fun runImportedRule(
        context: Context,
        payload: ChapterPayload,
        ruleText: String,
    ): RuleRunResult {
        val logs = arrayListOf<String>()
        val analysisModels = selectedModelProfiles(context)
        val dir = scriptDir(context, payload.bookName).apply { mkdirs() }
        val existingCharacters = readRoleManagerCharacters(dir)
        val payloadJson = JSONObject()
            .put("bookName", payload.bookName)
            .put("bookUrl", payload.bookUrl)
            .put("bookKey", payload.bookUrl)
            .put("chapterIndex", payload.chapterIndex)
            .put("chapterTitle", payload.chapterTitle)
            .put("chapterText", payload.chapterText)
            .put("characterRecords", existingCharacters.toRoleManagerJson())
            .put("voicePool", voicePoolJson())
            .put("analysisModel", analysisModels.firstOrNull()?.toScriptModelJson() ?: JSONObject.NULL)
            .put("analysisModels", analysisModels.toScriptModelJsonArray())

        val bindings = ScriptBindings().apply {
            this["ttsrv"] = CompatTtsrv(context, payload.bookName, logs)
            this["console"] = RuleConsole(logs)
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        RhinoScriptEngine.eval(extractRuleCode(ruleText), scope)
        val rawQueueJson = RhinoScriptEngine.eval(ruleCallJs(payloadJson), scope)?.toString().orEmpty()
        val rawLines = parseAudioQueue(rawQueueJson)
        val returnedCharacters = parseRuleCharacters(rawQueueJson)
        val locked = lockCharactersAndLines(
            existingCharacters = existingCharacters,
            suggestedCharacters = returnedCharacters + charactersFromLines(rawLines),
            rawLines = rawLines,
        )
        val analysis = Analysis(
            bookName = payload.bookName,
            chapterIndex = payload.chapterIndex,
            chapterTitle = payload.chapterTitle,
            characters = locked.characters,
            lines = locked.lines,
            updatedAt = System.currentTimeMillis(),
            source = "导入朗读规则",
        )
        save(context, analysis)
        File(dir, "last_audio_queue.json").writeText(rawQueueJson, Charsets.UTF_8)
        File(dir, "last_rule_log.txt").writeText(logs.joinToString("\n"), Charsets.UTF_8)
        return RuleRunResult(analysis, rawQueueJson, logs)
    }

    private fun moduleContext(
        payload: ChapterPayload,
        existingCharacters: List<ScriptCharacter>,
    ): JSONObject {
        return JSONObject()
            .put("bookName", payload.bookName)
            .put("bookUrl", payload.bookUrl)
            .put("bookKey", payload.bookUrl)
            .put("chapterIndex", payload.chapterIndex)
            .put("chapterTitle", payload.chapterTitle)
            .put("chapterText", payload.chapterText)
            .put("lines", JSONArray())
            .put("characters", existingCharacters.toRoleManagerJson())
            .put("logs", JSONArray())
            .put("voicePool", voicePoolJson())
    }

    private fun runAnalysisModule(
        context: Context,
        module: AnalysisModule,
        ctx: JSONObject,
        logs: MutableList<String>,
    ): JSONObject {
        val bindings = ScriptBindings().apply {
            this["console"] = RuleConsole(logs)
            this["brain"] = AnalysisBridge(context.applicationContext, logs)
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        val script = """
            (function() {
              var ctx = JSON.parse(${JSONObject.quote(ctx.toString())});
              ctx.logs = ctx.logs || [];
              ctx.lines = ctx.lines || [];
              ctx.characters = ctx.characters || [];
              ${module.code}
              if (typeof run !== 'function') {
                ctx.logs.push(${JSONObject.quote("${module.name} 未定义 run(ctx)，已跳过")});
                return JSON.stringify(ctx);
              }
              var ret = run(ctx) || ctx;
              ret.logs = ret.logs || ctx.logs || [];
              ret.lines = ret.lines || ctx.lines || [];
              ret.characters = ret.characters || ctx.characters || [];
              ret.chapterText = ret.chapterText || ctx.chapterText || "";
              return JSON.stringify(ret);
            })();
        """.trimIndent()
        val raw = RhinoScriptEngine.eval(script, scope)?.toString().orEmpty()
        return JSONObject(raw)
    }

    private fun moduleStats(ctx: JSONObject): ModuleStats {
        return ModuleStats(
            textLength = firstText(ctx, "chapterText").length,
            lineCount = ctx.optJSONArray("lines")?.length() ?: 0,
            characterCount = ctx.optJSONArray("characters")?.length() ?: 0,
        )
    }

    private fun moduleSampleLines(ctx: JSONObject, limit: Int = 5): List<String> {
        val lines = ctx.optJSONArray("lines") ?: return emptyList()
        return buildList {
            for (index in 0 until minOf(limit, lines.length())) {
                val line = lines.optJSONObject(index) ?: continue
                val roleName = firstText(line, "roleName", "speaker", "character").ifBlank { "旁白" }
                val voiceTag = firstText(line, "voiceTag", "voice", "tag").ifBlank { "待分配" }
                val text = firstText(line, "text", "content").replace(Regex("\\s+"), " ").take(80)
                add("${(index + 1).toString().padStart(2, '0')} $roleName / $voiceTag：$text")
            }
        }
    }

    private fun analysisFromModuleContext(
        payload: ChapterPayload,
        ctx: JSONObject,
        existingCharacters: List<ScriptCharacter>,
    ): Analysis {
        val chapterText = firstText(ctx, "chapterText").ifBlank { payload.chapterText }
        val rawLines = ctx.optJSONArray("lines")?.let {
            parseAudioQueue(JSONObject().put("audioQueue", it).toString())
        }.orEmpty()
        val lines = rawLines.ifEmpty {
            analyze(payload.bookName, payload.chapterIndex, payload.chapterTitle, chapterText).lines
        }.mapIndexed { index, line -> line.copy(index = index + 1) }
        val suggestedCharacters = ctx.optJSONArray("characters")
            ?.let { parseCharacterArray(it) }
            .orEmpty()
        val locked = lockCharactersAndLines(
            existingCharacters = existingCharacters,
            suggestedCharacters = suggestedCharacters + charactersFromLines(lines),
            rawLines = lines,
        )
        return Analysis(
            bookName = payload.bookName,
            chapterIndex = payload.chapterIndex,
            chapterTitle = payload.chapterTitle,
            characters = locked.characters,
            lines = locked.lines,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun ruleCallJs(payload: JSONObject): String {
        return """
            (function() {
                var payload = JSON.parse(${JSONObject.quote(payload.toString())});
                var fn = null;
                if (typeof SpeechRuleJS !== 'undefined' && SpeechRuleJS && typeof SpeechRuleJS.prepareChapterAudioQueue === 'function') {
                    fn = function(p) { return SpeechRuleJS.prepareChapterAudioQueue(p); };
                } else if (typeof prepareChapterAudioQueue === 'function') {
                    fn = prepareChapterAudioQueue;
                } else if (typeof __prepareChapterAudioQueue === 'function') {
                    fn = __prepareChapterAudioQueue;
                }
                if (!fn) {
                    throw '未找到 SpeechRuleJS.prepareChapterAudioQueue / prepareChapterAudioQueue';
                }
                var ret = fn(payload);
                return JSON.stringify(ret || []);
            })();
        """.trimIndent()
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

        return Analysis(
            bookName = bookName,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            characters = charactersFromLines(lines),
            lines = lines,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun parseAudioQueue(rawQueueJson: String): List<ScriptLine> {
        val trimmed = rawQueueJson.trim()
        if (trimmed.isBlank()) return emptyList()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val obj = JSONObject(trimmed)
            obj.optJSONArray("audioQueue")
                ?: obj.optJSONArray("queue")
                ?: obj.optJSONArray("items")
                ?: JSONArray()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = firstText(item, "text", "content", "sentence", "line")
                if (text.isBlank()) continue
                val tag = firstText(item, "tag", "role", "type")
                val characterInfo = item.optJSONObject("characterInfo")
                val rawRole = firstText(item, "roleName", "displayRoleName", "speaker", "character", "name")
                    .ifBlank { firstText(characterInfo, "name", "roleName", "speaker") }
                    .ifBlank { tag }
                val role = normalizeRoleName(rawRole, tag)
                val voice = firstText(item, "voice", "voiceTag", "displayVoice", "actualVoice", "sourceVoice")
                    .ifBlank { nestedVoice(item) }
                    .ifBlank { if (role == NARRATOR_ROLE) "旁白" else tag.takeIf { it.isUsefulVoiceTag() }.orEmpty() }
                    .ifBlank { "待分配" }
                add(
                    ScriptLine(
                        index = size + 1,
                        roleName = role,
                        voiceTag = voice,
                        isNarration = role == NARRATOR_ROLE || tag.equals("narration", ignoreCase = true),
                        text = text
                    )
                )
            }
        }
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
            voiceTag = "旁白",
            isNarration = true,
            text = text
        )
    }

    private fun parseRuleCharacters(rawQueueJson: String): List<ScriptCharacter> {
        val trimmed = rawQueueJson.trim()
        if (trimmed.isBlank() || trimmed.startsWith("[")) return emptyList()
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val direct = obj.optJSONArray("characters")
            ?: obj.optJSONArray("roles")
            ?: obj.optJSONArray("roleRecords")
            ?: obj.optJSONArray("characterRecords")
        val fromArray = direct?.let { parseCharacterArray(it) }.orEmpty()
        val roleMap = obj.optJSONObject("roleMap") ?: obj.optJSONObject("charactersMap")
        val fromMap = roleMap?.let { parseCharacterMap(it) }.orEmpty()
        return (fromArray + fromMap)
            .filter { it.name.isNotBlank() && it.name != NARRATOR_ROLE && !it.name.isUnknownRoleName() }
            .distinctBy { it.name }
    }

    private fun parseCharacterArray(array: JSONArray): List<ScriptCharacter> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseCharacterObject(item)?.let { add(it) }
            }
        }
    }

    private fun parseCharacterMap(map: JSONObject): List<ScriptCharacter> {
        return buildList {
            val keys = map.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val item = map.optJSONObject(name) ?: continue
                parseCharacterObject(item, fallbackName = name)?.let { add(it) }
            }
        }
    }

    private fun parseCharacterObject(item: JSONObject, fallbackName: String = ""): ScriptCharacter? {
        val name = firstText(item, "name", "roleName", "character", "speaker", "label")
            .ifBlank { fallbackName }
            .trim()
        if (name.isBlank() || name == NARRATOR_ROLE || name.isUnknownRoleName()) return null
        val gender = firstText(item, "gender").ifBlank { "待定" }
        val age = firstText(item, "ageType", "age", "genderAge").ifBlank { voicePoolPrefix("", gender) }
        val voice = firstText(item, "voiceTag", "voice", "displayVoice", "actualVoice", "selectedVoice")
        val normalizedVoice = normalizeVoiceTag(voice, age, gender)
        val voiceInfo = inferCharacterFromVoiceTag(normalizedVoice)
        return ScriptCharacter(
            name = name,
            gender = voiceInfo.first.ifBlank { gender },
            ageType = voiceInfo.second.ifBlank { age.toRoleManagerAge(gender) },
            voiceTag = normalizedVoice.ifBlank { "待分配" },
        )
    }

    private fun lockCharactersAndLines(
        existingCharacters: List<ScriptCharacter>,
        suggestedCharacters: List<ScriptCharacter>,
        rawLines: List<ScriptLine>,
    ): LockedAnalysisData {
        val existingByName = existingCharacters
            .filter { it.name.isNotBlank() && it.name != NARRATOR_ROLE && !it.name.isUnknownRoleName() }
            .associateBy { it.name }
        val occupied = linkedMapOf<String, String>()
        existingCharacters.forEach { character ->
            val voice = normalizeVoiceTag(character.voiceTag, character.ageType, character.gender)
            if (voice.isLockedVoiceTag()) occupied[voice] = character.name
        }

        val finalCharacters = linkedMapOf<String, ScriptCharacter>()
        fun ensureCharacter(roleName: String, suggestion: ScriptCharacter? = null, suggestedVoice: String = ""): ScriptCharacter {
            finalCharacters[roleName]?.let { return it }
            val existing = existingByName[roleName]
            val base = suggestion ?: existing ?: inferCharacter(roleName)
            val preferredVoice = normalizeVoiceTag(
                existing?.voiceTag?.takeIf { it.isLockedVoiceTag() } ?: suggestedVoice.ifBlank { base.voiceTag },
                existing?.ageType ?: base.ageType,
                existing?.gender ?: base.gender,
            )
            val finalVoice = if (preferredVoice.isLockedVoiceTag() && occupied[preferredVoice]?.let { it == roleName } != false) {
                occupied[preferredVoice] = roleName
                preferredVoice
            } else {
                allocateVoiceTag(
                    roleName = roleName,
                    suggestedVoice = preferredVoice,
                    ageType = existing?.ageType ?: base.ageType,
                    gender = existing?.gender ?: base.gender,
                    occupied = occupied,
                )
            }
            val voiceInfo = inferCharacterFromVoiceTag(finalVoice)
            val finalCharacter = ScriptCharacter(
                name = roleName,
                gender = voiceInfo.first.ifBlank { existing?.gender ?: base.gender },
                ageType = voiceInfo.second.ifBlank { existing?.ageType ?: base.ageType.toRoleManagerAge(base.gender) },
                voiceTag = finalVoice,
            )
            finalCharacters[roleName] = finalCharacter
            return finalCharacter
        }

        suggestedCharacters
            .filter { it.name.isNotBlank() && it.name != NARRATOR_ROLE && !it.name.isUnknownRoleName() }
            .distinctBy { it.name }
            .forEach { ensureCharacter(it.name, suggestion = it, suggestedVoice = it.voiceTag) }

        val finalLines = rawLines.map { line ->
            if (line.isNarration || line.roleName == NARRATOR_ROLE || line.roleName.isUnknownRoleName()) {
                line.copy(roleName = NARRATOR_ROLE, voiceTag = "旁白", isNarration = true)
            } else {
                val character = ensureCharacter(line.roleName, suggestedVoice = line.voiceTag)
                line.copy(voiceTag = character.voiceTag)
            }
        }
        return LockedAnalysisData(finalCharacters.values.toList(), finalLines)
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

    private fun charactersFromLines(lines: List<ScriptLine>): List<ScriptCharacter> {
        return lines
            .asSequence()
            .filterNot { it.isNarration }
            .filter { it.roleName.isNotBlank() && !it.roleName.isUnknownRoleName() }
            .groupBy { it.roleName }
            .map { (name, roleLines) ->
                val base = inferCharacter(name)
                val voiceTag = roleLines
                    .map { it.voiceTag }
                    .firstOrNull { it.isNotBlank() && it != "待分配" }
                    .orEmpty()
                val voiceInfo = inferCharacterFromVoiceTag(voiceTag)
                ScriptCharacter(
                    name = name,
                    gender = voiceInfo.first.ifBlank { base.gender },
                    ageType = voiceInfo.second.ifBlank { base.ageType },
                    voiceTag = voiceTag.ifBlank { base.voiceTag },
                )
            }
            .toList()
    }

    private fun inferCharacterFromVoiceTag(voiceTag: String): Pair<String, String> {
        if (voiceTag.isBlank() || voiceTag == "待分配") return "" to ""
        val gender = when {
            voiceTag.contains("女") || voiceTag.contains("幼女") -> "女"
            voiceTag.contains("男") -> "男"
            else -> ""
        }
        val ageType = when {
            voiceTag.contains("女童") || voiceTag.contains("幼女") || voiceTag.contains("女孩") -> "女/女童"
            voiceTag.contains("男童") || voiceTag.contains("男孩") -> "男/男童"
            voiceTag.contains("女老年") -> "女/女老年"
            voiceTag.contains("男老年") -> "男/男老年"
            voiceTag.contains("女中年") -> "女/女中年"
            voiceTag.contains("男中年") -> "男/男中年"
            voiceTag.contains("少女") -> "女/少女"
            voiceTag.contains("少年") -> "男/少年"
            voiceTag.contains("女青年") -> "女/女青年"
            voiceTag.contains("男青年") -> "男/男青年"
            else -> ""
        }
        return gender to ageType
    }

    private fun normalizeVoiceTag(voiceTag: String, ageType: String, gender: String): String {
        val value = voiceTag.trim().replace(Regex("\\s+"), "")
        if (value.isBlank() || value.isGenericVoiceTag()) return ""
        if (value == "旁白" || value.equals("narration", ignoreCase = true)) return "旁白"
        if (value.matches(Regex("^[男女]/.+\\d{2,3}$"))) return value
        if (value.matches(Regex("^[男女]/.+$"))) return "${value}01"
        val short = Regex("^(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊男|特殊女)(\\d{2,3})?$")
            .matchEntire(value)
        if (short != null) {
            val prefix = voicePoolPrefix(short.groupValues[1], gender)
            val suffix = short.groupValues.getOrNull(2).orEmpty().ifBlank { "01" }
            return "$prefix$suffix"
        }
        val prefix = voicePoolPrefix(ageType, gender)
        return "${prefix}01"
    }

    private fun voicePoolPrefix(ageType: String, gender: String): String {
        val value = ageType.trim().replace(Regex("\\s+"), "")
        var inferredGender = gender.trim()
        if (value.matches(Regex("^男/(男童|少年|男青年|男中年|男老年|特殊)$"))) return value
        if (value.matches(Regex("^女/(女童|少女|女青年|女中年|女老年|特殊)$"))) return value
        if (value.contains("女")) inferredGender = "女"
        if (value.contains("男")) inferredGender = "男"
        return when {
            Regex("老年|老人|老者|老翁|老汉|爷爷|七旬|八旬|六旬|古稀|花甲").containsMatchIn(value) ->
                if (inferredGender == "女") "女/女老年" else "男/男老年"
            Regex("女童|小女孩|女娃|幼女").containsMatchIn(value) -> "女/女童"
            Regex("男童|小男孩|男娃|童").containsMatchIn(value) -> "男/男童"
            Regex("少女|姑娘|丫头|女学生").containsMatchIn(value) -> "女/少女"
            Regex("少年|小伙子|男学生").containsMatchIn(value) -> "男/少年"
            Regex("女中年|中年妇|妇人|妇女").containsMatchIn(value) -> "女/女中年"
            Regex("男中年|中年|壮年|汉子|大汉|管事|掌柜|管家|官员|将军").containsMatchIn(value) ->
                if (inferredGender == "女") "女/女中年" else "男/男中年"
            Regex("女青年|女子|女人").containsMatchIn(value) -> "女/女青年"
            Regex("男青年|男子|男人").containsMatchIn(value) -> "男/男青年"
            Regex("青年|年轻").containsMatchIn(value) -> if (inferredGender == "女") "女/女青年" else "男/男青年"
            value.contains("特殊") -> if (inferredGender == "女") "女/特殊" else "男/特殊"
            inferredGender == "女" -> "女/女青年"
            else -> "男/男青年"
        }
    }

    private fun allocateVoiceTag(
        roleName: String,
        suggestedVoice: String,
        ageType: String,
        gender: String,
        occupied: MutableMap<String, String>,
    ): String {
        val suggested = normalizeVoiceTag(suggestedVoice, ageType, gender)
        if (suggested.isLockedVoiceTag() && occupied[suggested]?.let { it == roleName } != false) {
            occupied[suggested] = roleName
            return suggested
        }
        val prefix = voicePoolPrefix(ageType, gender)
        for (index in 1..voicePoolCapacity(prefix)) {
            val voice = "$prefix${index.toString().padStart(2, '0')}"
            if (!occupied.containsKey(voice)) {
                occupied[voice] = roleName
                return voice
            }
        }
        return suggested.ifBlank { "${prefix}01" }
    }

    private fun voicePoolCapacity(prefix: String): Int {
        return when {
            prefix.contains("青年") -> 200
            prefix.contains("特殊") -> 20
            else -> 100
        }
    }

    private fun String.isLockedVoiceTag(): Boolean {
        return isNotBlank() && !isGenericVoiceTag() && this != "旁白"
    }

    private fun String.isGenericVoiceTag(): Boolean {
        return isBlank()
                || equals("待分配", ignoreCase = true)
                || equals("对话", ignoreCase = true)
                || equals("dialogue", ignoreCase = true)
                || equals("duihua", ignoreCase = true)
                || equals("narration", ignoreCase = true)
    }

    private fun normalizeRoleName(role: String, tag: String): String {
        val value = role.trim()
        if (value.equals("narration", ignoreCase = true) || value == "旁白") return NARRATOR_ROLE
        if (value.isUnknownRoleName() || value.equals("duihua", ignoreCase = true) || value.equals("dialogue", ignoreCase = true)) {
            return UNKNOWN_ROLE
        }
        if (value.isNotBlank()) return value
        if (tag.equals("narration", ignoreCase = true)) return NARRATOR_ROLE
        return UNKNOWN_ROLE
    }

    private fun nestedVoice(item: JSONObject): String {
        val config = item.optJSONObject("config") ?: return ""
        val source = config.optJSONObject("source") ?: return ""
        return firstText(source, "voice", "voiceTag", "name")
    }

    private fun firstText(json: JSONObject?, vararg keys: String): String {
        if (json == null) return ""
        for (key in keys) {
            val value = json.opt(key)
            if (value != null && value != JSONObject.NULL) {
                val text = value.toString().trim()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun String.isUsefulVoiceTag(): Boolean {
        return isNotBlank()
                && !equals("narration", ignoreCase = true)
                && !equals("duihua", ignoreCase = true)
                && !equals("dialogue", ignoreCase = true)
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

    private fun String.isUnknownRoleName(): Boolean {
        val value = trim()
        return value.isBlank()
                || value == UNKNOWN_ROLE
                || value == "未知角色"
                || value == "未知发言人"
                || value == "未知"
                || value.equals("unknown", ignoreCase = true)
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
            childKeywords.any { name.contains(it) } -> if (gender == "女") "女/女童" else "男/男童"
            oldKeywords.any { name.contains(it) } -> if (gender == "女") "女/女老年" else "男/男老年"
            gender == "女" -> "女/女青年"
            else -> "男/男青年"
        }
        val voiceTag = when {
            ageType == "女/女童" -> "女/女童01"
            ageType == "男/男童" -> "男/男童01"
            ageType == "女/女老年" -> "女/女老年01"
            ageType == "男/男老年" -> "男/男老年01"
            ageType == "女/女青年" -> "女/女青年01"
            ageType == "男/男青年" -> "男/男青年01"
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
        syncRoleManagerFiles(dir, analysis)
    }

    private fun Analysis.toJson(): JSONObject {
        return JSONObject()
            .put("bookName", bookName)
            .put("chapterIndex", chapterIndex)
            .put("chapterTitle", chapterTitle)
            .put("updatedAt", updatedAt)
            .put("source", source)
            .put("error", error)
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

    private fun List<AnalysisModelProfile>.toScriptModelJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { profile -> array.put(profile.toScriptModelJson()) }
        }
    }

    private fun voicePoolJson(): JSONArray {
        val configs = listOf(
            "女/少女" to 100,
            "男/少年" to 100,
            "女/女青年" to 200,
            "男/男青年" to 200,
            "女/女中年" to 100,
            "男/男中年" to 100,
            "女/女老年" to 100,
            "男/男老年" to 100,
            "女/女童" to 100,
            "男/男童" to 100,
            "男/特殊" to 20,
            "女/特殊" to 20,
        )
        return JSONArray().also { array ->
            configs.forEach { (prefix, count) ->
                array.put(
                    JSONObject()
                        .put("prefix", prefix)
                        .put("count", count)
                        .put("example", "${prefix}01")
                )
            }
        }
    }

    private fun AnalysisModelProfile.toScriptModelJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("provider", provider)
            .put("modelUrl", modelUrl)
            .put("baseUrl", modelUrl)
            .put("modelName", modelName)
            .put("model", modelName)
            .put("modelKey", modelKey)
            .put("apiKey", modelKey)
    }

    private fun List<ScriptCharacter>.toRoleManagerJson(): JSONArray {
        return JSONArray().also { array ->
            forEach { character ->
                if (character.name == NARRATOR_ROLE) return@forEach
                array.put(
                    JSONObject()
                        .put("name", character.name)
                        .put("aliases", character.name)
                        .put("gender", character.gender)
                        .put("age", character.ageType.toRoleManagerAge(character.gender))
                        .put("ageType", character.ageType)
                        .put("voice", character.voiceTag)
                        .put("voiceTag", character.voiceTag)
                        .put("usageCount", 100)
                        .put("source", "内置分析模式")
                )
            }
        }
    }

    private fun syncRoleManagerFiles(dir: File, analysis: Analysis) {
        val bookFileName = "shuming.${analysis.bookName.safeFileName()}.json"
        val mergedCharacters = mergeRoleCharacters(dir, analysis.characters)
        val records = mergedCharacters.toRoleManagerJson()
        File(dir, "characterRecords.json").writeText(records.toString(2), Charsets.UTF_8)
        File(dir, bookFileName).writeText(records.toString(2), Charsets.UTF_8)
        File(dir, "cunfang.txt").writeText(analysis.bookName, Charsets.UTF_8)
        File(dir, "gengxin.json").writeText(
            JSONObject()
                .put("bookName", analysis.bookName)
                .put("chapterIndex", analysis.chapterIndex)
                .put("chapterTitle", analysis.chapterTitle)
                .put("updatedAt", analysis.updatedAt)
                .put("count", mergedCharacters.size)
                .put("source", analysis.source)
                .toString(2),
            Charsets.UTF_8
        )
        File(dir, "liebiao.json").writeText(
            JSONArray().put(analysis.bookName).toString(2),
            Charsets.UTF_8
        )
        File(dir, "fayinren.json").writeText(
            JSONArray().also { array ->
                mergedCharacters
                    .map { it.voiceTag }
                    .filter { it.isNotBlank() && it != "待分配" }
                    .distinct()
                    .forEach { array.put(it) }
            }.toString(2),
            Charsets.UTF_8
        )
    }

    private fun mergeRoleCharacters(dir: File, current: List<ScriptCharacter>): List<ScriptCharacter> {
        val merged = linkedMapOf<String, ScriptCharacter>()
        readRoleManagerCharacters(dir).forEach { character ->
            if (character.name.isNotBlank() && character.name != NARRATOR_ROLE) {
                merged[character.name] = character
            }
        }
        current.forEach { character ->
            if (character.name.isBlank() || character.name == NARRATOR_ROLE) return@forEach
            val old = merged[character.name]
            merged[character.name] = when {
                old == null -> character
                old.voiceTag.isLockedVoiceTag() -> old.copy(
                    gender = old.gender.ifBlank { character.gender },
                    ageType = old.ageType.ifBlank { character.ageType },
                )
                else -> character
            }
        }
        return merged.values.toList()
    }

    private fun readRoleManagerCharacters(dir: File): List<ScriptCharacter> {
        val file = File(dir, "characterRecords.json")
        if (!file.exists()) return emptyList()
        val array = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = firstText(item, "name", "roleName", "character")
                if (name.isBlank()) continue
                val gender = firstText(item, "gender").ifBlank { "待定" }
                val age = firstText(item, "ageType", "age").ifBlank { "青年" }
                val voice = firstText(item, "voiceTag", "voice", "displayVoice").ifBlank { "待分配" }
                add(ScriptCharacter(name, gender, age, voice))
            }
        }
    }

    private fun String.toRoleManagerAge(gender: String): String {
        return when {
            matches(Regex("^[男女]/.+")) -> this
            contains("女童") || contains("幼女") || contains("女孩") -> "女/女童"
            contains("男童") || contains("男孩") -> "男/男童"
            contains("女老年") -> "女/女老年"
            contains("男老年") -> "男/男老年"
            contains("女中年") -> "女/女中年"
            contains("男中年") -> "男/男中年"
            contains("少女") -> "女/少女"
            contains("少年") -> "男/少年"
            contains("女青年") -> "女/女青年"
            contains("男青年") -> "男/男青年"
            contains("童") || contains("儿童") -> if (gender == "女") "女/女童" else "男/男童"
            contains("老") -> if (gender == "女") "女/女老年" else "男/男老年"
            contains("中") -> if (gender == "女") "女/女中年" else "男/男中年"
            contains("少") -> if (gender == "女") "女/少女" else "男/少年"
            else -> if (gender == "女") "女/女青年" else "男/男青年"
        }
    }

    private fun ruleInfoFromRaw(raw: String): ImportedRuleInfo {
        val code = extractRuleCode(raw)
        val json = raw.takeIf { it.trim().startsWith("{") }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        return ImportedRuleInfo(
            name = firstText(json, "name", "ruleName", "title", "displayName").ifBlank { "导入朗读规则" },
            author = firstText(json, "author", "creator", "user").ifBlank { "未知" },
            version = firstText(json, "version", "versionName", "updateTime").ifBlank { "未标注" },
            isJson = json != null,
            rawLength = raw.length,
            codeLength = code.length,
        )
    }

    private fun saveRuleToLibrary(context: Context, normalized: String) {
        val info = ruleInfoFromRaw(normalized)
        val id = "${info.name}_${info.author}_${info.version}_${Integer.toHexString(normalized.hashCode())}"
            .safeFileName()
        val dir = ruleLibraryDir(context).apply { mkdirs() }
        File(dir, "$id.json").writeText(normalized, Charsets.UTF_8)
    }

    private fun defaultAnalysisModules(): List<AnalysisModule> {
        return listOf(
            AnalysisModule(
                id = "text_preprocess",
                name = "文本预处理",
                code = defaultModuleCode("text_preprocess", "文本预处理")
            ),
            AnalysisModule(
                id = "ad_clean_regex",
                name = "广告清理（本地正则）",
                type = "regex",
                code = defaultModuleCode("ad_clean_regex", "广告清理（本地正则）")
            ),
            AnalysisModule(
                id = "dialogue_split",
                name = "对话切分",
                code = defaultModuleCode("dialogue_split", "对话切分")
            ),
            AnalysisModule(
                id = "speaker_resolve",
                name = "说话人归属",
                code = defaultModuleCode("speaker_resolve", "说话人归属")
            ),
            AnalysisModule(
                id = "alias_merge",
                name = "角色别名合并",
                code = defaultModuleCode("alias_merge", "角色别名合并")
            ),
            AnalysisModule(
                id = "voice_tag",
                name = "音色标签分配",
                code = defaultModuleCode("voice_tag", "音色标签分配")
            ),
            AnalysisModule(
                id = "emotion",
                name = "情绪分析",
                code = defaultModuleCode("emotion", "情绪分析")
            ),
            AnalysisModule(
                id = "validate",
                name = "结果校验",
                code = defaultModuleCode("validate", "结果校验")
            ),
        )
    }

    private fun parseAnalysisModules(raw: String): List<AnalysisModule> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        val array = runCatching {
            if (text.startsWith("[")) {
                JSONArray(text)
            } else {
                val obj = JSONObject(text)
                obj.optJSONArray("modules")
                    ?: obj.optJSONArray("analysisModules")
                    ?: obj.optJSONArray("pipeline")
                    ?: JSONArray()
            }
        }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = firstText(item, "name", "title", "label").ifBlank { "模块 ${index + 1}" }
                val id = firstText(item, "id", "key").ifBlank { "${name}_${index}".safeFileName() }
                add(
                    AnalysisModule(
                        id = id,
                        name = name,
                        type = firstText(item, "type").ifBlank { "js" },
                        enabled = item.optBoolean("enabled", true),
                        code = firstText(item, "code", "js", "script", "content", "source"),
                    )
                )
            }
        }
    }

    private fun AnalysisModule.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("type", type)
            .put("enabled", enabled)
            .put("code", code)
    }

    private fun List<AnalysisModule>.toJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { array.put(it.toJson()) }
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun Analysis.toQueueJson(): JSONObject {
        return JSONObject()
            .put("bookName", bookName)
            .put("chapterIndex", chapterIndex)
            .put("chapterTitle", chapterTitle)
            .put("source", source)
            .put(
                "characters",
                JSONArray().also { array ->
                    characters.forEach { character ->
                        array.put(
                            JSONObject()
                                .put("name", character.name)
                                .put("gender", character.gender)
                                .put("ageType", character.ageType)
                                .put("voiceTag", character.voiceTag)
                        )
                    }
                }
            )
            .put(
                "audioQueue",
                JSONArray().also { array ->
                    lines.forEach { line ->
                        array.put(
                            JSONObject()
                                .put("index", line.index)
                                .put("text", line.text)
                                .put("roleName", line.roleName)
                                .put("voiceTag", line.voiceTag)
                                .put("tag", if (line.isNarration) "narration" else line.roleName)
                                .put("isNarration", line.isNarration)
                        )
                    }
                }
            )
    }

    private fun extractRuleCode(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("{")) return text
        return runCatching {
            val json = JSONObject(text)
            firstText(json, "code", "js", "script", "content", "source").ifBlank { text }
        }.getOrDefault(text)
    }

    private fun normalizeImportedRule(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("{")) {
            return runCatching { JSONObject(text).toString(2) }.getOrDefault(text)
        }
        return JSONObject()
            .put("name", "手动 JS 朗读规则")
            .put("type", "speech_rule")
            .put("code", text)
            .toString(2)
    }

    private fun importedRuleFile(context: Context): File {
        return File(globalDir(context), "imported_speech_rule.js")
    }

    private fun importedRuleJsonFile(context: Context): File {
        return File(globalDir(context), "imported_speech_rule.json")
    }

    private fun ruleLibraryDir(context: Context): File {
        return File(globalDir(context), "rule-library")
    }

    private fun globalDir(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(root, "script-brain")
    }

    private fun scriptDir(context: Context, bookName: String): File {
        return File(globalDir(context), bookName.safeFileName())
    }

    private fun normalizeChatCompletionsUrl(url: String): String {
        val normalized = url.trim().trimEnd('/')
        if (normalized.endsWith("/chat/completions", ignoreCase = true)) return normalized
        return "$normalized/chat/completions"
    }

    private const val KEY_MODEL_PROFILES = "script_brain_model_profiles"
    private const val KEY_SELECTED_MODEL_PROFILE = "script_brain_selected_model_profile"
    private const val KEY_SELECTED_MODEL_PROFILES = "script_brain_selected_model_profiles"
    private const val KEY_ANALYSIS_MODULES = "script_brain_analysis_modules"
    private const val KEY_ANALYSIS_MODULES_VERSION = "script_brain_analysis_modules_version"
    private const val DEFAULT_ANALYSIS_MODULE_VERSION = 2

    private fun String.safeFileName(): String {
        return replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").take(80).ifBlank { "未命名" }
    }

    private data class ChapterPayload(
        val bookName: String,
        val bookUrl: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val chapterText: String,
    )

    class AnalysisBridge(
        private val context: Context,
        private val logs: MutableList<String>,
    ) {
        fun hasModel(): Boolean {
            return selectedModelProfiles(context.applicationContext).isNotEmpty()
        }

        fun chatJson(prompt: String?): String {
            val profiles = selectedModelProfiles(context.applicationContext)
            require(profiles.isNotEmpty()) { "还没有配置分析模型" }
            var lastError = ""
            profiles.forEach { profile ->
                runCatching {
                    callModel(profile, prompt.orEmpty())
                }.onSuccess { raw ->
                    val jsonText = extractJsonText(raw)
                    if (jsonText.isNotBlank()) {
                        logs.add("brain: ${profile.name.ifBlank { profile.modelName }} 返回 JSON ${jsonText.length} 字")
                        return jsonText
                    }
                    lastError = "模型返回不是 JSON"
                }.onFailure {
                    lastError = it.localizedMessage ?: it.javaClass.simpleName
                    logs.add("brain: ${profile.name.ifBlank { profile.modelName }} 失败：$lastError")
                }
            }
            error(lastError.ifBlank { "模型未返回可解析 JSON" })
        }

        private fun callModel(profile: AnalysisModelProfile, prompt: String): String {
            val body = JSONObject()
                .put("model", profile.modelName.trim())
                .put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("role", "system")
                                .put("content", "你是中文小说有声书多角色朗读分析器。只输出可解析 JSON，不要 Markdown，不要解释。")
                        )
                        .put(JSONObject().put("role", "user").put("content", prompt))
                )
                .put("temperature", 0.1)
                .toString()
            val request = Request.Builder()
                .url(normalizeChatCompletionsUrl(profile.modelUrl))
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .header("Content-Type", "application/json")
                .apply {
                    if (profile.modelKey.isNotBlank()) {
                        header("Authorization", "Bearer ${profile.modelKey.trim()}")
                    }
                }
                .build()
            return okHttpClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: ${text.take(240)}")
                }
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return text
                val choice = obj.optJSONArray("choices")?.optJSONObject(0)
                val message = choice?.optJSONObject("message")
                firstText(message, "content")
                    .ifBlank { firstText(message, "reasoning_content") }
                    .ifBlank { text }
            }
        }

        private fun extractJsonText(raw: String): String {
            var text = raw.trim()
            text = text
                .removePrefix("```json")
                .removePrefix("```JSON")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val objectStart = text.indexOf('{')
            val arrayStart = text.indexOf('[')
            val start = listOf(objectStart, arrayStart).filter { it >= 0 }.minOrNull() ?: return ""
            val endChar = if (text[start] == '{') '}' else ']'
            val end = text.lastIndexOf(endChar)
            if (end <= start) return ""
            return text.substring(start, end + 1).trim()
        }
    }

    class RuleConsole(private val logs: MutableList<String>) {
        fun log(vararg messages: Any?) {
            logs.add("log: ${messages.joinToString(" ") { it.orEmptyText() }}")
        }

        fun warn(vararg messages: Any?) {
            logs.add("warn: ${messages.joinToString(" ") { it.orEmptyText() }}")
        }

        fun error(vararg messages: Any?) {
            logs.add("error: ${messages.joinToString(" ") { it.orEmptyText() }}")
        }
    }

    class CompatTtsrv(
        private val context: Context,
        private val bookName: String,
        private val logs: MutableList<String>,
    ) {
        val tts = TtsCompat()

        fun readTxtFile(fileName: String?): String {
            val file = resolveFile(fileName)
            return file.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
        }

        fun writeTxtFile(fileName: String?, content: String?) {
            val file = resolveFile(fileName)
            file.parentFile?.mkdirs()
            file.writeText(content.orEmpty(), Charsets.UTF_8)
            logs.add("writeTxtFile: ${file.name}")
        }

        fun httpGet(url: String?): CompatResponse {
            val request = Request.Builder().url(url.orEmpty()).get().build()
            return okHttpClient.newCall(request).execute().use { response ->
                CompatResponse(response.code, response.body.string())
            }
        }

        fun httpPost(url: String?, body: String?): CompatResponse {
            return httpPost(url, body, null)
        }

        fun httpPost(url: String?, body: String?, headers: Any?): CompatResponse {
            val headersMap = headerMap(headers)
            val mediaType = headersMap["Content-Type"]?.toMediaTypeOrNull()
                ?: "application/json; charset=utf-8".toMediaTypeOrNull()
            val builder = Request.Builder()
                .url(url.orEmpty())
                .post(body.orEmpty().toRequestBody(mediaType))
            headersMap.forEach { (name, value) ->
                if (!name.equals("Content-Type", ignoreCase = true)) {
                    builder.addHeader(name, value)
                }
            }
            return okHttpClient.newCall(builder.build()).execute().use { response ->
                CompatResponse(response.code, response.body.string())
            }
        }

        private fun resolveFile(fileName: String?): File {
            val safeName = fileName.orEmpty()
                .replace("\\", "/")
                .split("/")
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/")
                .ifBlank { "empty.txt" }
            val dir = scriptDir(context, bookName)
            return File(dir, safeName)
        }

        private fun headerMap(headers: Any?): Map<String, String> {
            if (headers == null) return emptyMap()
            val unwrapped = if (headers is Wrapper) headers.unwrap() else headers
            if (unwrapped is Map<*, *>) {
                return unwrapped.mapNotNull { (key, value) ->
                    val name = key?.toString().orEmpty()
                    val text = value?.toString().orEmpty()
                    if (name.isBlank()) null else name to text
                }.toMap()
            }
            if (unwrapped is Scriptable) {
                return unwrapped.ids.mapNotNull { id ->
                    val name = id?.toString().orEmpty()
                    val value = ScriptableObject.getProperty(unwrapped, name)
                    val text = value?.toString().orEmpty()
                    if (name.isBlank() || text == "undefined") null else name to text
                }.toMap()
            }
            return emptyMap()
        }
    }

    class TtsCompat {
        val data: MutableMap<String, Any?> = linkedMapOf()
    }

    class CompatResponse(private val codeValue: Int, private val text: String) {
        fun code(): Int = codeValue
        fun body(): CompatBody = CompatBody(text)
    }

    class CompatBody(private val text: String) {
        fun string(): String = text
    }

    private fun Any?.orEmptyText(): String {
        return this?.toString().orEmpty()
    }

    private val quoteRegex = Regex("[“「『](.*?)[”」』]")
    private val speakerRegex = Regex("""([\u4e00-\u9fa5A-Za-z0-9·]{1,8})(?:轻声|低声|大声|冷冷|淡淡|笑着|皱眉|急忙|连忙|忍不住|忽然|沉声|柔声|小声)?(?:说道|说|问道|问|道|喊道|喊|叫道|叫|笑道|怒道|喝道|答道|回答|嘀咕|喃喃|开口)$""")
    private val pronouns = setOf("我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "众人", "男人", "女人", "少年", "少女")
    private val femaleKeywords = listOf("女", "娘", "妹", "姐", "姑", "婆", "姨", "母", "月", "雪", "柔", "婉")
    private val maleKeywords = listOf("男", "哥", "叔", "伯", "父", "爷", "掌柜", "凡", "轩", "辰", "峰")
    private val childKeywords = listOf("小", "童", "孩", "娃")
    private const val ROLE_MANAGER_PLUGIN_ASSET = "defaultData/scriptBrain/role_manager_plugin_v2667.json"
    private val ROLE_MANAGER_FILES = listOf(
        "characterRecords.json",
        "shuming.<book>.json",
        "gengxin.json",
        "cunfang.txt",
        "liebiao.json",
        "fayinren.json",
    )
    private val oldKeywords = listOf("老", "婆", "爷", "伯", "叔", "掌柜", "嬷")
    private const val NARRATOR_ROLE = "旁白"
    private const val UNKNOWN_ROLE = "角色待定"
}
