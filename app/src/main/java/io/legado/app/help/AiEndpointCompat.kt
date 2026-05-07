package io.legado.app.help

object AiEndpointCompat {

    /**
     * 兼容用户输入：
     * 1. open.bigmodel.cn/api/paas/v4
     * 2. https://open.bigmodel.cn/api/paas/v4
     * 3. https://open.bigmodel.cn/api/paas/v4/chat/completions
     * 4. https://api.openai.com/v1
     * 5. https://api.openai.com/v1/chat/completions
     */
    fun chatCompletionsUrl(input: String?): String {
        var url = input.orEmpty().trim()
        if (url.isBlank()) return url

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        url = url.trimEnd('/')

        val lower = url.lowercase()

        if (
            lower.endsWith("/chat/completions") ||
            lower.endsWith("/v1/chat/completions") ||
            lower.endsWith("/v4/chat/completions") ||
            lower.endsWith("/responses") ||
            lower.endsWith("/v1/responses")
        ) {
            return url
        }

        return "$url/chat/completions"
    }

    fun authHeader(apiKey: String?): String {
        val key = apiKey.orEmpty().trim()
        return if (key.startsWith("Bearer ", ignoreCase = true)) {
            key
        } else {
            "Bearer $key"
        }
    }
}
