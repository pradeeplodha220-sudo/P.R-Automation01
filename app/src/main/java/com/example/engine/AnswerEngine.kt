package com.example.engine

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

data class QOpt(
    val text: String,
    val index: Int,
    val bounds: Rect,
    val node: AccessibilityNodeInfo? = null
)

data class QuizData(
    val question: String,
    val options: List<QOpt>,
    val fingerprint: String
)

object AnswerEngine {
    private val db = HashMap<String, String>()
    private var loaded = false

    private fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val raw = c.getSharedPreferences("local_answer_cache", Context.MODE_PRIVATE).getString("data", "{}") ?: "{}"
            val obj = JSONObject(raw)
            val it = obj.keys()
            while (it.hasNext()) {
                val key = it.next()
                db[norm(key)] = obj.optString(key)
            }
        }
        AutomationState.log("Local answer DB: ${db.size} entries")
    }

    fun solve(c: Context, q: QuizData): Int {
        load(c)

        // FAST PATH 1: local cache
        db[norm(q.question)]?.let { answer ->
            val idx = resolve(answer, q)
            if (idx >= 0) {
                AutomationState.log("FAST ANSWER SOURCE: local cache • option ${idx + 1}")
                return idx
            }
        }
        // FAST PATH 2: rule engines
        translationEngine(q).takeIf { it >= 0 }?.let {
            AutomationState.log("FAST ANSWER SOURCE: translation • option ${it + 1}")
            return it
        }
        antonymEngine(q).takeIf { it >= 0 }?.let {
            AutomationState.log("FAST ANSWER SOURCE: antonym • option ${it + 1}")
            return it
        }
        synonymEngine(q).takeIf { it >= 0 }?.let {
            AutomationState.log("FAST ANSWER SOURCE: synonym • option ${it + 1}")
            return it
        }
        arithmeticEngine(q).takeIf { it >= 0 }?.let {
            AutomationState.log("FAST ANSWER SOURCE: arithmetic • option ${it + 1}")
            return it
        }
        optionLetterEngine(q).takeIf { it >= 0 }?.let {
            AutomationState.log("FAST ANSWER SOURCE: option-letter • option ${it + 1}")
            return it
        }
        prepositionEngine(q).takeIf { it >= 0 }?.let {
            AutomationState.log("FAST ANSWER SOURCE: preposition • option ${it + 1}")
            return it
        }
        linguisticEngine(q).takeIf { it >= 0 }?.let {
            AutomationState.log("FAST ANSWER SOURCE: linguistic • option ${it + 1}")
            return it
        }
        tokenEngine(q).takeIf { it >= 0 }?.let {
            AutomationState.log("FAST ANSWER SOURCE: token • option ${it + 1}")
            return it
        }

        // PRIMARY AI: Groq (ultra-fast responses)
        GroqAIEngine.solve(c, q).takeIf { it >= 0 }?.let {
            AutomationState.log("ANSWER SOURCE: Groq • option ${it + 1}")
            return it
        }

        // FALLBACK AI: Gemini
        GeminiAIEngine.solve(c, q).takeIf { it >= 0 }?.let {
            AutomationState.log("ANSWER SOURCE: Gemini • option ${it + 1}")
            return it
        }

        // FALLBACK 3: Fuzzy cache
        val qw = words(q.question)
        for ((k, v) in db) {
            val kw = words(k)
            if (kw.isEmpty()) continue
            val score = qw.intersect(kw).size.toDouble() / max(qw.union(kw).size, 1)
            if (score >= 0.55) {
                val idx = resolve(v, q)
                if (idx >= 0) return idx
            }
        }

        return -1
    }

    private fun optionLetterEngine(q: QuizData): Int {
        val x = norm(q.question)
        val m = Regex("(?:answer|option|choose|select)\\s*[:#-]?\\s*([a-d])\\b", RegexOption.IGNORE_CASE).find(x)
        return m?.groupValues?.getOrNull(1)?.firstOrNull()?.let { it - 'a' }?.takeIf { it in q.options.indices } ?: -1
    }

    private fun translationEngine(q: QuizData): Int {
        val x = norm(q.question)
        val map = mapOf(
            "नमस्ते" to setOf("hello", "hi", "greetings"),
            "धन्यवाद" to setOf("thank you", "thanks"),
            "अलविदा" to setOf("goodbye", "bye"),
            "शुभ प्रभात" to setOf("good morning"),
            "शुभ रात्रि" to setOf("good night"),
            "पानी" to setOf("water"),
            "किताब" to setOf("book"),
            "घर" to setOf("house", "home"),
            "स्कूल" to setOf("school"),
            "सूरज" to setOf("sun"),
            "चाँद" to setOf("moon", "the moon"),
            "आकाश" to setOf("sky"),
            "फूल" to setOf("flower"),
            "पेड़" to setOf("tree"),
            "दोस्त" to setOf("friend"),
            "माँ" to setOf("mother", "mom"),
            "पिता" to setOf("father", "dad"),
            "खाना" to setOf("food"),
            "दूध" to setOf("milk"),
            "काला" to setOf("black"),
            "सफेद" to setOf("white"),
            "लाल" to setOf("red"),
            "हरा" to setOf("green")
        )
        if (!Regex("english|translation|translate|meaning|अनुवाद|अर्थ|का मतलब|का अंग्रेजी", RegexOption.IGNORE_CASE).containsMatchIn(x)) return -1
        for ((h, answers) in map) {
            if (x.contains(h)) {
                val idx = q.options.indexOfFirst { o -> answers.any { a -> norm(o.text) == a || norm(o.text).contains(a) } }
                if (idx >= 0) return idx
            }
        }
        return -1
    }

    private fun antonymEngine(q: QuizData): Int {
        val x = norm(q.question)
        val direct = mapOf(
            "come" to setOf("go", "leave", "depart"),
            "arrive" to setOf("leave", "depart"),
            "enter" to setOf("exit", "leave"),
            "stay" to setOf("leave", "go")
        )
        if (Regex("antonym|opposite|विलोम|विपरीत|उल्टा अर्थ|विपरीतार्थक", RegexOption.IGNORE_CASE).containsMatchIn(x)) {
            for ((w, a) in direct) if (Regex("\\b${Regex.escape(w)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(x)) {
                val idx = q.options.indexOfFirst { o -> a.any { v -> norm(o.text) == v || norm(o.text).contains(v) } }
                if (idx >= 0) return idx
            }
        }
        if (!Regex("antonym|opposite|विलोम|विपरीत|उल्टा अर्थ|विपरीतार्थक", RegexOption.IGNORE_CASE).containsMatchIn(x)) return -1
        val pairs = mapOf(
            "big" to setOf("small", "little"), "small" to setOf("big", "large"),
            "hot" to setOf("cold"), "cold" to setOf("hot"),
            "fast" to setOf("slow"), "slow" to setOf("fast"),
            "happy" to setOf("sad"), "sad" to setOf("happy"),
            "old" to setOf("new", "young"), "new" to setOf("old"),
            "easy" to setOf("difficult", "hard"), "early" to setOf("late"),
            "up" to setOf("down"), "down" to setOf("up"),
            "true" to setOf("false"), "good" to setOf("bad"),
            "bad" to setOf("good"), "high" to setOf("low"),
            "light" to setOf("dark", "heavy"), "dark" to setOf("light"),
            "day" to setOf("night"), "night" to setOf("day"),
            "near" to setOf("far"), "far" to setOf("near"),
            "open" to setOf("closed", "close"), "closed" to setOf("open"),
            "strong" to setOf("weak"), "weak" to setOf("strong")
        )
        for ((w, a) in pairs) {
            if (Regex("\\b${Regex.escape(w)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(x)) {
                val idx = q.options.indexOfFirst { o -> a.any { v -> norm(o.text) == v || norm(o.text).contains(v) } }
                if (idx >= 0) return idx
            }
        }
        return -1
    }

    private fun synonymEngine(q: QuizData): Int {
        val x = norm(q.question)
        if (!Regex("synonym|similar|same meaning|पर्याय|समानार्थी|समान अर्थ", RegexOption.IGNORE_CASE).containsMatchIn(x)) return -1
        val pairs = mapOf(
            "big" to setOf("large", "huge"), "small" to setOf("little", "tiny"),
            "happy" to setOf("glad", "joyful"), "fast" to setOf("quick", "rapid"),
            "smart" to setOf("clever", "intelligent"), "begin" to setOf("start", "commence"),
            "end" to setOf("finish", "stop"), "help" to setOf("assist"),
            "beautiful" to setOf("pretty", "lovely"), "angry" to setOf("mad", "furious")
        )
        for ((w, a) in pairs) if (Regex("\\b${Regex.escape(w)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(x)) {
            val idx = q.options.indexOfFirst { o -> a.any { v -> norm(o.text) == v || norm(o.text).contains(v) } }
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun arithmeticEngine(q: QuizData): Int {
        val x = norm(q.question)
        val m = Regex("(?:what is|calculate|solve|find|how much is|कितना होगा)\\s*(\\d+)\\s*([+\\-x×*÷/])\\s*(\\d+)", RegexOption.IGNORE_CASE).find(x) ?: return -1
        val a = m.groupValues[1].toLong()
        val b = m.groupValues[3].toLong()
        val op = m.groupValues[2]
        val ans = when (op) {
            "+" -> a + b
            "-" -> a - b
            "x", "×", "*" -> a * b
            "÷", "/" -> if (b != 0L) a / b else Long.MIN_VALUE
            else -> Long.MIN_VALUE
        }
        if (ans == Long.MIN_VALUE) return -1
        return q.options.indexOfFirst { o -> norm(o.text).replace(",", "") == ans.toString() }
    }

    private fun prepositionEngine(q: QuizData): Int {
        val x = norm(q.question)
        val opts = q.options.map { norm(it.text) }
        val patterns = listOf(
            Regex("""\bbook is\s+(?:_+|blank)\s+the table\b""") to "on",
            Regex("""\b(?:book|pen|phone|cup|bag|key) is\s+(?:_+|blank)\s+the table\b""") to "on",
            Regex("""\b(?:cat|dog|child|person) is\s+(?:_+|blank)\s+the table\b""") to "under",
            Regex("""\b(?:in|inside)\s+the\s+(?:room|box|bag|house)\b""") to "in"
        )
        for ((rx, answer) in patterns) {
            if (rx.containsMatchIn(x)) {
                val idx = opts.indexOfFirst { it == answer }
                if (idx >= 0) return idx
            }
        }
        if (x.contains("the table")) {
            val idx = opts.indexOfFirst { it == "on" }
            if (idx >= 0 && (x.contains("book") || x.contains("pen") || x.contains("phone") || x.contains("cup"))) return idx
        }
        return -1
    }

    private fun linguisticEngine(q: QuizData): Int {
        val x = norm(q.question)
        val hindi = mapOf(
            "लड़का" to setOf("boy"), "लड़की" to setOf("girl"),
            "पुरुष" to setOf("man"), "महिला" to setOf("woman"),
            "बच्चा" to setOf("child"), "कुत्ता" to setOf("dog"),
            "बिल्ली" to setOf("cat"), "आम" to setOf("mango"),
            "सेब" to setOf("apple"), "पृथ्वी" to setOf("earth"),
            "सूर्य" to setOf("sun"), "चंद्रमा" to setOf("moon")
        )
        for ((h, a) in hindi) if (x.contains(h)) {
            val idx = q.options.indexOfFirst { o -> a.any { v -> norm(o.text) == v || norm(o.text).contains(v) } }
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun tokenEngine(q: QuizData): Int {
        val stop = setOf("what", "which", "is", "the", "correct", "answer", "choose", "select", "of", "a", "an", "to", "for", "word", "following", "translation", "meaning", "question", "option")
        val qw = words(q.question).filter { it !in stop }.toSet()
        var best = -1
        var bestScore = 0.0
        for (i in q.options.indices) {
            val o = q.options[i]
            val ow = words(o.text)
            val inter = qw.intersect(ow).size.toDouble()
            val score = if (qw.isEmpty()) 0.0 else inter / qw.size
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return if (best >= 0 && bestScore >= 0.60) best else -1
    }

    private fun parseModelAnswer(text: String, q: QuizData): Int {
        val t = text.trim()
        val explicit = Regex("""(?is)\b(?:option|answer|choice)\s*[:#-]?\s*([A-H]|\d{1,2})\b""").find(t)
        if (explicit != null) {
            val v = explicit.groupValues[1]
            v.toIntOrNull()?.let {
                if (it in 1..q.options.size) return it - 1
                if (it in q.options.indices) return it
            }
            v.firstOrNull()?.let {
                val idx = it.uppercaseChar() - 'A'
                if (idx in q.options.indices) return idx
            }
        }
        Regex("""(?i)^\s*[\(\[]?([A-H])[\)\]]?\s*[.:-]?\s*$""").find(t)?.groupValues?.getOrNull(1)?.firstOrNull()?.let {
            val idx = it.uppercaseChar() - 'A'
            if (idx in q.options.indices) return idx
        }
        Regex("""^\s*(\d{1,2})\s*$""").find(t)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
            if (it in 1..q.options.size) return it - 1
            if (it in q.options.indices) return it
        }
        val low = norm(t)
        q.options.forEachIndexed { i, o ->
            val ot = norm(o.text)
            if (ot.isNotBlank() && (low == ot || (low.contains(ot) && ot.length >= 2))) return i
        }
        return -1
    }

    private fun cooldownActive(p: android.content.SharedPreferences, prefix: String, slot: Int): Boolean {
        return System.currentTimeMillis() < p.getLong("${prefix}_cooldown_$slot", 0L)
    }

    private fun markDailyCooldown(p: android.content.SharedPreferences, prefix: String, slot: Int) {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        p.edit().putLong("${prefix}_cooldown_$slot", cal.timeInMillis).apply()
    }

    object GroqAIEngine {
        private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

        private fun keys(c: Context): List<Pair<String, String>> {
            val p = c.getSharedPreferences(AutomationState.PREFS, Context.MODE_PRIVATE)
            val out = mutableListOf<Pair<String, String>>()
            runCatching {
                val a = JSONArray(p.getString("groq_keys", "[]") ?: "[]")
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val k = o.optString("key", "").trim()
                    if (k.isNotBlank()) out.add(k to o.optString("model", "openai/gpt-oss-20b").ifBlank { "openai/gpt-oss-20b" })
                }
            }
            if (out.isEmpty()) {
                val k = p.getString("groq_key", "")?.trim().orEmpty()
                if (k.isNotBlank()) out.add(k to (p.getString("groq_model", "openai/gpt-oss-20b") ?: "openai/gpt-oss-20b"))
            }
            if (out.isEmpty() && com.example.BuildConfig.GROQ_API_KEY.isNotBlank() && com.example.BuildConfig.GROQ_API_KEY != "your_groq_api_key") {
                out.add(com.example.BuildConfig.GROQ_API_KEY to "openai/gpt-oss-20b")
            }
            return out
        }

        fun solve(c: Context, q: QuizData): Int {
            val p = c.getSharedPreferences(AutomationState.PREFS, Context.MODE_PRIVATE)
            val list = keys(c)
            if (list.isEmpty()) return -1
            for ((slot, pair) in list.withIndex()) {
                if (cooldownActive(p, "groq", slot)) continue
                val key = pair.first
                val model = pair.second.ifBlank { "openai/gpt-oss-20b" }
                val result = runCatching {
                    val prompt = buildString {
                        append("Solve the EXACT multiple-choice question below. Ignore app headers, timers, difficulty labels, instructions, and buttons. Return ONLY one option letter A-H.\n")
                        append("QUESTION: ").append(q.question)
                        append("\nOPTIONS:\n")
                        q.options.forEachIndexed { i, o -> append(('A'.code + i).toChar()).append(": ").append(o.text).append("\n") }
                        append("ANSWER:")
                    }
                    val messages = JSONArray()
                        .put(JSONObject().put("role", "system").put("content", "Answer the exact question supplied by the user. Return only one valid option letter."))
                        .put(JSONObject().put("role", "user").put("content", prompt))
                    val bodyObj = JSONObject()
                        .put("model", model)
                        .put("messages", messages)
                        .put("temperature", 0)
                        .put("max_completion_tokens", 256)
                    val conn = (java.net.URL(ENDPOINT).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 1200
                        readTimeout = 2200
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Bearer $key")
                    }
                    conn.outputStream.use { it.write(bodyObj.toString().toByteArray()) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    conn.disconnect()
                    if (code !in 200..299) {
                        if (code == 429 || code == 402 || text.contains("rate limit", true)) markDailyCooldown(p, "groq", slot)
                        return@runCatching -1
                    }
                    val content = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "").orEmpty()
                    parseModelAnswer(content, q)
                }.getOrElse { -1 }
                if (result >= 0) return result
            }
            return -1
        }
    }

    object GeminiAIEngine {
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

        private fun keys(c: Context): List<Pair<String, String>> {
            val p = c.getSharedPreferences(AutomationState.PREFS, Context.MODE_PRIVATE)
            val out = mutableListOf<Pair<String, String>>()
            runCatching {
                val a = JSONArray(p.getString("gemini_keys", "[]") ?: "[]")
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val k = o.optString("key", "").trim()
                    if (k.isNotBlank()) out.add(k to o.optString("model", "gemini-3.8-flash"))
                }
            }
            if (out.isEmpty()) {
                val k = p.getString("gemini_key", "")?.trim().orEmpty()
                if (k.isNotBlank()) out.add(k to (p.getString("gemini_model", "gemini-3.8-flash") ?: "gemini-3.8-flash"))
            }
            if (out.isEmpty() && com.example.BuildConfig.GEMINI_API_KEY.isNotBlank() && com.example.BuildConfig.GEMINI_API_KEY != "your_gemini_api_key") {
                out.add(com.example.BuildConfig.GEMINI_API_KEY to "gemini-3.8-flash")
            }
            return out
        }

        fun solve(c: Context, q: QuizData): Int {
            val p = c.getSharedPreferences(AutomationState.PREFS, Context.MODE_PRIVATE)
            val list = keys(c)
            if (list.isEmpty()) return -1
            for ((slot, pair) in list.withIndex()) {
                if (cooldownActive(p, "gemini", slot)) continue
                val key = pair.first
                val model = pair.second.ifBlank { "gemini-3.8-flash" }
                val result = runCatching {
                    val prompt = buildString {
                        append("Solve the EXACT multiple-choice question below. Ignore app headers, timers, difficulty labels, instructions, and buttons. Return ONLY one option letter A-H.\n")
                        append("QUESTION: ").append(q.question).append("\nOPTIONS:\n")
                        for (i in q.options.indices) {
                            append(('A'.code + i).toChar()).append(": ").append(q.options[i].text).append("\n")
                        }
                    }
                    val contents = JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    )
                    val gen = JSONObject().put("maxOutputTokens", 256)
                    val body = JSONObject().put("contents", contents).put("generationConfig", gen)
                    val u = BASE + java.net.URLEncoder.encode(model, "UTF-8") + ":generateContent"
                    val conn = (java.net.URL(u).openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 1200
                        readTimeout = 2200
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("x-goog-api-key", key)
                    }
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    conn.disconnect()
                    if (code !in 200..299) {
                        if (code == 429 || code == 402 || text.contains("rate limit", true)) markDailyCooldown(p, "gemini", slot)
                        return@runCatching -1
                    }
                    val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val answerText = parts?.let { arr -> (0 until arr.length()).joinToString(" ") { i -> arr.optJSONObject(i)?.optString("text", "").orEmpty() } }.orEmpty()
                    parseModelAnswer(answerText, q)
                }.getOrElse { -1 }
                if (result >= 0) return result
            }
            return -1
        }
    }

    private fun resolve(a: String, q: QuizData): Int {
        val x = norm(a)
        x.toIntOrNull()?.let { if (it in q.options.indices) return it }
        for (i in q.options.indices) {
            val y = norm(q.options[i].text)
            if (y == x || y.contains(x) || x.contains(y)) return i
        }
        return -1
    }

    private fun words(s: String) = Regex("[\\p{L}\\p{N}]+").findAll(norm(s)).map { it.value }.filter { it.length >= 2 }.toSet()

    fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
