var SpeechRuleJS = (function () {
    var RULE_NAME = "阅读端有声书模式朗读规则";
    var RULE_VERSION = "1.0.2";
    var NARRATOR = "旁白";
    var UNKNOWN = "未知角色";

    function log(message) {
        try {
            if (typeof console !== "undefined" && console && console.log) {
                console.log("[有声书规则] " + message);
            }
        } catch (e) {
        }
    }

    function normalizeText(text) {
        return String(text || "")
            .replace(/\r\n/g, "\n")
            .replace(/\r/g, "\n")
            .replace(/\u3000/g, " ")
            .replace(/[ \t]+\n/g, "\n")
            .replace(/\n{3,}/g, "\n\n")
            .trim();
    }

    function normalizeLine(text) {
        return String(text || "")
            .replace(/\s+/g, " ")
            .replace(/^[\s　]+|[\s　]+$/g, "");
    }

    function splitByPunctuation(text, maxLen) {
        var value = normalizeLine(text);
        if (!value) return [];
        var result = [];
        var buf = "";
        for (var i = 0; i < value.length; i++) {
            var ch = value.charAt(i);
            buf += ch;
            if ("。！？!?；;…".indexOf(ch) >= 0 || buf.length >= maxLen) {
                pushChunk(result, buf, maxLen);
                buf = "";
            }
        }
        pushChunk(result, buf, maxLen);
        return result;
    }

    function pushChunk(result, text, maxLen) {
        var value = normalizeLine(text);
        if (!value) return;
        while (value.length > maxLen) {
            result.push(value.substring(0, maxLen));
            value = value.substring(maxLen);
        }
        if (value) result.push(value);
    }

    function inferSpeaker(beforeText, afterText, lastSpeaker) {
        var before = normalizeLine(beforeText).slice(-42);
        var after = normalizeLine(afterText).slice(0, 42);
        var patterns = [
            /([\u4e00-\u9fa5A-Za-z0-9·]{1,8}?)(?:低声|轻声|冷声|沉声|柔声|怒声|笑着|哭着|急声|淡淡|缓缓|认真|皱眉|点头|摇头)*(?:叹息|开口|问|说|道|喊|叫|答|骂|嘀咕|喃喃|补充|解释|提醒|打断|喝道|吼道)[:：，。！？、；\s]*$/,
            /(?:只听|听见|却听|便听|忽听)([\u4e00-\u9fa5A-Za-z0-9·]{1,8})(?:低声|轻声|冷声|沉声|柔声|怒声|笑着|哭着|问|说|道|喊|叫|答|骂|嘀咕|喃喃)/,
            /^([\u4e00-\u9fa5A-Za-z0-9·]{1,8}?)(?:低声|轻声|冷声|沉声|柔声|怒声|笑着|哭着)*(?:问|说|道|喊|叫|答|骂|嘀咕|喃喃)[:：，。！？、；\s]*/
        ];
        var found = matchSpeaker(before, patterns);
        if (!found && after.indexOf("“") < 0 && after.indexOf("\"") < 0) {
            found = matchSpeaker(after, patterns);
        }
        if (found) return found;
        if (lastSpeaker && lastSpeaker !== UNKNOWN) return lastSpeaker;
        return UNKNOWN;
    }

    function matchSpeaker(text, patterns) {
        for (var i = 0; i < patterns.length; i++) {
            var match = patterns[i].exec(text);
            if (match && isValidSpeakerName(match[1])) {
                return cleanSpeakerName(match[1]);
            }
        }
        return "";
    }

    function cleanSpeakerName(name) {
        return String(name || "")
            .replace(/(低声|轻声|冷声|沉声|柔声|怒声|笑着|哭着|急声|淡淡|缓缓|认真|皱眉|叹息|点头|摇头)+$/g, "")
            .replace(/^[\s　]+|[\s　]+$/g, "");
    }

    function isValidSpeakerName(name) {
        if (!name) return false;
        if (name.length < 1 || name.length > 8) return false;
        var bad = {
            "我": true, "你": true, "他": true, "她": true, "它": true, "他们": true, "她们": true,
            "有人": true, "众人": true, "对方": true, "那人": true, "这个": true, "那个": true,
            "声音": true, "女子": true, "男人": true, "女人": true
        };
        return !bad[name];
    }

    function inferCharacter(name) {
        if (name === NARRATOR) {
            return { name: NARRATOR, gender: "旁白", ageType: "旁白", voiceTag: "旁白" };
        }
        if (!name || name === UNKNOWN) {
            return { name: UNKNOWN, gender: "待定", ageType: "男/男青年", voiceTag: "男/男青年01" };
        }
        var gender = "待定";
        var ageType = "男/男青年";
        if (/娘|母|妈|姨|婶|嫂|姐|妹|女|妃|后|姬|嫣|月|雪|灵|兰|花|凤|瑶|璃|薇|姑娘|小姐|夫人|婆婆|丫头/.test(name)) {
            gender = "女";
            ageType = "女/女青年";
        } else if (/父|爹|叔|伯|哥|弟|男|王爷|公子|先生|郎|将军|掌柜|师兄|老爷|少爷/.test(name)) {
            gender = "男";
            ageType = "男/男青年";
        }
        if (/小|童|孩|娃|丫头/.test(name)) {
            ageType = gender === "女" ? "女/女童" : "男/男童";
        } else if (/老|婆婆|爷爷|奶奶|老人/.test(name)) {
            ageType = gender === "女" ? "女/女老年" : "男/男老年";
        } else if (/叔|伯|婶|姨|掌柜|夫人|先生|师父|父|母/.test(name)) {
            ageType = gender === "女" ? "女/女中年" : "男/男中年";
        } else if (/少|少年|少女/.test(name)) {
            ageType = gender === "女" ? "女/少女" : "男/少年";
        }
        if (gender === "待定") {
            if (/^男/.test(ageType) || ageType === "少年") gender = "男";
            if (/^女/.test(ageType) || ageType === "少女") gender = "女";
        }
        return {
            name: name,
            gender: gender,
            ageType: ageType,
            voiceTag: defaultVoice(gender, ageType)
        };
    }

    function defaultVoice(gender, ageType) {
        var prefix = voicePoolPrefix(ageType, gender);
        return prefix + "01";
    }

    function voicePoolPrefix(ageType, gender) {
        var s = String(ageType || "").replace(/\s+/g, "");
        gender = String(gender || "");
        if (/^男\/(?:男童|少年|男青年|男中年|男老年|特殊)$/.test(s)) return s;
        if (/^女\/(?:女童|少女|女青年|女中年|女老年|特殊)$/.test(s)) return s;
        if (/女/.test(s)) gender = "女";
        if (/男/.test(s)) gender = "男";
        if (/老年|老人|老者|老翁|老汉|爷爷|七旬|八旬|六旬|古稀|花甲/.test(s)) return gender === "女" ? "女/女老年" : "男/男老年";
        if (/女童|小女孩|女娃|幼女/.test(s)) return "女/女童";
        if (/男童|小男孩|男娃|童/.test(s)) return "男/男童";
        if (/少女|姑娘|丫头|女学生/.test(s)) return "女/少女";
        if (/少年|小伙子|男学生/.test(s)) return "男/少年";
        if (/女中年|中年妇|妇人|妇女/.test(s)) return "女/女中年";
        if (/男中年|中年|壮年|汉子|大汉|管事|掌柜|管家|官员|将军/.test(s)) return gender === "女" ? "女/女中年" : "男/男中年";
        if (/女青年|女子|女人/.test(s)) return "女/女青年";
        if (/男青年|男子|男人/.test(s)) return "男/男青年";
        if (/青年|年轻/.test(s)) return gender === "女" ? "女/女青年" : "男/男青年";
        if (/特殊/.test(s)) return gender === "女" ? "女/特殊" : "男/特殊";
        if (gender === "女") return "女/女青年";
        return "男/男青年";
    }

    function normalizeVoiceTag(voiceTag, ageType, gender) {
        var v = String(voiceTag || "").replace(/\s+/g, "");
        if (/^(男|女)\/.+\d{2,3}$/.test(v)) return v;
        if (/^(男|女)\/.+$/.test(v)) return v + "01";
        var m = /^(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊男|特殊女)(\d{2,3})?$/.exec(v);
        if (m) {
            var prefix = voicePoolPrefix(m[1], gender);
            return prefix + (m[2] || "01");
        }
        return defaultVoice(gender, ageType);
    }

    function makeItem(index, roleName, text, tag, emotion) {
        var character = inferCharacter(roleName);
        return {
            index: index,
            text: text,
            roleName: character.name,
            displayRoleName: character.name,
            voice: character.voiceTag,
            voiceTag: character.voiceTag,
            displayVoice: character.voiceTag,
            tag: tag,
            emotion: emotion || "neutral",
            speed: 1.0,
            pitch: 1.0,
            volume: 1.0,
            characterInfo: {
                name: character.name,
                gender: character.gender,
                ageType: character.ageType,
                voiceTag: character.voiceTag
            }
        };
    }

    function buildLocalQueue(chapterText) {
        var text = normalizeText(chapterText);
        var paragraphs = text.split(/\n+/);
        var queue = [];
        var lastSpeaker = "";
        for (var p = 0; p < paragraphs.length; p++) {
            var paragraph = normalizeLine(paragraphs[p]);
            if (!paragraph) continue;
            var quoteReg = /[“"]([^”"]{1,600})[”"]/g;
            var cursor = 0;
            var match;
            while ((match = quoteReg.exec(paragraph)) !== null) {
                var before = paragraph.substring(cursor, match.index);
                var dialogue = normalizeLine(match[1]);
                var after = paragraph.substring(quoteReg.lastIndex);
                addNarration(queue, before);
                if (dialogue) {
                    var speaker = inferSpeaker(before, after, lastSpeaker);
                    if (speaker !== UNKNOWN) lastSpeaker = speaker;
                    queue.push(makeItem(queue.length + 1, speaker, dialogue, "dialogue", inferEmotion(dialogue)));
                }
                cursor = quoteReg.lastIndex;
            }
            var tail = paragraph.substring(cursor);
            addNarration(queue, tail);
        }
        return queue;
    }

    function addNarration(queue, text) {
        var parts = splitByPunctuation(text, 120);
        for (var i = 0; i < parts.length; i++) {
            queue.push(makeItem(queue.length + 1, NARRATOR, parts[i], "narration", inferEmotion(parts[i])));
        }
    }

    function inferEmotion(text) {
        if (/怒|吼|骂|杀|血|恨|咬牙|冷声/.test(text)) return "angry";
        if (/哭|泪|悲|痛|哽咽|难过|伤心/.test(text)) return "sad";
        if (/笑|哈哈|轻松|玩笑|调侃/.test(text)) return "happy";
        if (/怕|恐|惊|慌|颤|诡异|阴冷/.test(text)) return "fear";
        if (/急|快|追|跑|冲|打|战|危险/.test(text)) return "tense";
        return "neutral";
    }

    function improveCharactersByModel(payload, queue) {
        var model = pickModel(payload);
        if (!model) return queue;
        var names = collectRoleNames(queue);
        if (!names.length) return queue;
        var snippets = collectSnippets(queue, names);
        var prompt = [
            "你是小说有声书角色音色分配器。请只输出 JSON，不要 Markdown，不要解释。",
            "任务：根据角色名和台词片段，判断每个角色的 gender、ageType、voiceTag。",
            "gender 只能是：男、女、待定。",
            "ageType 必须从这套朗读规则定义里选择：男/男童、女/女童、男/少年、女/少女、男/男青年、女/女青年、男/男中年、女/女中年、男/男老年、女/女老年、待定。",
            "voiceTag 必须从这些带路径标签中选择：男/男童01、女/女童01、男/少年01、女/少女01、男/男青年01、女/女青年01、男/男中年01、女/女中年01、男/男老年01、女/女老年01。",
            "如果无法判断，voiceTag 使用男/男青年01，gender 使用待定，ageType 使用男/男青年。",
            "输出格式：{\"characters\":[{\"name\":\"张三\",\"gender\":\"男\",\"ageType\":\"男/男青年\",\"voiceTag\":\"男/男青年01\"}]}",
            "角色和片段：",
            JSON.stringify(snippets)
        ].join("\n");
        try {
            var content = callChatModel(model, prompt);
            var data = parseJsonLoose(content);
            var list = data && (data.characters || data.roles || data.items || data);
            if (!list || typeof list.length === "undefined") return queue;
            var map = {};
            for (var i = 0; i < list.length; i++) {
                var role = list[i] || {};
                var name = String(role.name || role.roleName || role.character || "");
                if (!name) continue;
                map[name] = {
                    gender: String(role.gender || "待定"),
                    ageType: voicePoolPrefix(role.ageType || role.age || "男/男青年", role.gender || "待定"),
                    voiceTag: normalizeVoiceTag(role.voiceTag || role.voice || "", role.ageType || role.age || "男/男青年", role.gender || "待定")
                };
            }
            for (var q = 0; q < queue.length; q++) {
                var item = queue[q];
                var info = map[item.roleName];
                if (!info || item.roleName === NARRATOR) continue;
                item.voice = info.voiceTag;
                item.voiceTag = info.voiceTag;
                item.displayVoice = info.voiceTag;
                item.characterInfo = {
                    name: item.roleName,
                    gender: info.gender,
                    ageType: info.ageType,
                    voiceTag: info.voiceTag
                };
            }
            log("AI 已优化角色音色：" + Object.keys(map).length + " 个");
        } catch (e) {
            log("AI 角色优化失败，保留本地规则：" + e);
        }
        return queue;
    }

    function collectRoleNames(queue) {
        var seen = {};
        var names = [];
        for (var i = 0; i < queue.length; i++) {
            var name = queue[i].roleName;
            if (!name || name === NARRATOR || name === UNKNOWN || seen[name]) continue;
            seen[name] = true;
            names.push(name);
        }
        return names;
    }

    function collectSnippets(queue, names) {
        var result = [];
        var nameMap = {};
        for (var i = 0; i < names.length; i++) nameMap[names[i]] = [];
        for (var q = 0; q < queue.length; q++) {
            var item = queue[q];
            if (!nameMap[item.roleName]) continue;
            if (nameMap[item.roleName].length < 3) {
                nameMap[item.roleName].push(item.text);
            }
        }
        for (var n = 0; n < names.length; n++) {
            result.push({ name: names[n], samples: nameMap[names[n]] || [] });
        }
        return result;
    }

    function pickModel(payload) {
        var models = payload && payload.analysisModels;
        if (models && models.length) return models[0];
        if (payload && payload.analysisModel && payload.analysisModel !== null) return payload.analysisModel;
        return null;
    }

    function callChatModel(model, prompt) {
        var baseUrl = String(model.modelUrl || model.baseUrl || "").replace(/\/+$/, "");
        var modelName = String(model.modelName || model.model || "");
        var apiKey = String(model.modelKey || model.apiKey || "");
        if (!baseUrl || !modelName) throw "未配置分析模型";
        var url = /\/chat\/completions$/i.test(baseUrl) ? baseUrl : baseUrl + "/chat/completions";
        var body = JSON.stringify({
            model: modelName,
            temperature: 0.1,
            messages: [
                { role: "system", content: "你只输出可解析 JSON。" },
                { role: "user", content: prompt }
            ]
        });
        var headers = { "Content-Type": "application/json" };
        if (apiKey) headers.Authorization = "Bearer " + apiKey;
        var response = ttsrv.httpPost(url, body, headers);
        var text = response.body().string();
        if (response.code() < 200 || response.code() >= 300) {
            throw "HTTP " + response.code() + ": " + text.substring(0, 200);
        }
        var json = JSON.parse(text);
        var choice = json.choices && json.choices[0];
        var message = choice && choice.message;
        return String((message && (message.content || message.reasoning_content)) || "");
    }

    function parseJsonLoose(text) {
        var raw = String(text || "").trim();
        if (!raw) throw "模型返回空内容";
        raw = raw.replace(/^```json\s*/i, "").replace(/^```\s*/i, "").replace(/```$/i, "").trim();
        var startArray = raw.indexOf("[");
        var startObj = raw.indexOf("{");
        var start = -1;
        if (startArray >= 0 && startObj >= 0) start = Math.min(startArray, startObj);
        else start = Math.max(startArray, startObj);
        if (start > 0) raw = raw.substring(start);
        var endArray = raw.lastIndexOf("]");
        var endObj = raw.lastIndexOf("}");
        var end = Math.max(endArray, endObj);
        if (end >= 0) raw = raw.substring(0, end + 1);
        return JSON.parse(raw);
    }

    function normalizeQueue(queue) {
        var result = [];
        for (var i = 0; i < queue.length; i++) {
            var item = queue[i];
            if (!item || !item.text) continue;
            item.index = result.length + 1;
            if (!item.roleName) item.roleName = NARRATOR;
            if (!item.voice && item.voiceTag) item.voice = item.voiceTag;
            if (!item.voiceTag && item.voice) item.voiceTag = item.voice;
            if (!item.displayVoice) item.displayVoice = item.voiceTag || item.voice || "旁白";
            result.push(item);
        }
        return result;
    }

    return {
        name: RULE_NAME,
        version: RULE_VERSION,
        prepareChapterAudioQueue: function (payload) {
            payload = payload || {};
            log("开始分析：" + (payload.bookName || "") + " / " + (payload.chapterTitle || ""));
            var queue = buildLocalQueue(payload.chapterText || "");
            queue = improveCharactersByModel(payload, queue);
            queue = normalizeQueue(queue);
            log("完成台词本：" + queue.length + " 句");
            return queue;
        }
    };
})();
