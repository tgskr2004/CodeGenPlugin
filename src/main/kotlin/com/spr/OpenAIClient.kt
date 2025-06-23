package com.spr

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

data class LLMResult(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

object OpenAIClient {

    private const val MODEL = "gpt-3.5-turbo"
    private val gson: Gson = Gson()
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val apiKey: String? = "test"

    fun generateServiceFromDto(dtoSource: String, dtoClassName: String): LLMResult? {
        val prompt = """
            You are a senior Java backend developer.
            Given the following Java DTO class, generate a realistic Java Service class that:
            - Uses this DTO in one or more methods
            - Has basic business logic (e.g., validation, transformation)
            - Has at least one dependency (e.g., repository, fraud checker, validator)
            - Uses exception handling where needed
            - Returns meaningful output (e.g., boolean, string, response DTO)
            - Uses proper method and class naming, clean code, and Java best practices
            
            DTO:
            ```java
            $dtoSource
            ```
        """.trimIndent()

        return sendOpenAIRequest(prompt)
    }

    fun generateTests(dtoClassName: String, dtoSource: String, serviceSource: String): LLMResult? {
        val prompt = """
            You are a senior Java developer and test engineer.

            Generate a JUnit 5 test class for the following DTO and Service class.
            Requirements:
            - Mock external dependencies (repositories, validators, etc.)
            - Cover all logic paths: happy path, edge cases, invalid input
            - Follow AAA structure (Arrange, Act, Assert)
            - Use clear method names like shouldDoX_whenY
            - Use org.junit.jupiter.api and Mockito
            - The class name should end with 'GeneratedTest'
            - Ensure the output compiles cleanly

            DTO:
            ```java
            $dtoSource
            ```

            SERVICE:
            ```java
            $serviceSource
            ```
        """.trimIndent()

        return sendOpenAIRequest(prompt)
    }

    private fun sendOpenAIRequest(prompt: String): LLMResult? {
        if (apiKey.isNullOrBlank()) return null

        val payload = mapOf(
            "model" to MODEL,
            "temperature" to 0.2,
            "messages" to listOf(
                mapOf("role" to "system", "content" to "You are a helpful and experienced Java backend engineer."),
                mapOf("role" to "user", "content" to prompt)
            )
        )

        val requestBody = gson.toJson(payload)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null

            val json: JsonObject = gson.fromJson(response.body(), JsonObject::class.java)
            val choices = json.getAsJsonArray("choices")
            val usage = json.getAsJsonObject("usage")

            if (choices.isEmpty) return null

            val content = choices[0].asJsonObject
                .getAsJsonObject("message")
                .get("content")
                .asString
                .trim()
                .cleanJavaClassContent()

            val promptTokens = usage?.get("prompt_tokens")?.asInt ?: 0
            val completionTokens = usage?.get("completion_tokens")?.asInt ?: 0
            val totalTokens = usage?.get("total_tokens")?.asInt ?: 0

            return LLMResult(
                content = content,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens
            )

        } catch (ex: Exception) {
            ex.printStackTrace()
            null
        }
    }

    private fun String.cleanJavaClassContent(): String {
        return this
            .substringAfter("```java", "")
            .substringBeforeLast("```")
            .lines()
            .dropWhile { it.trim().startsWith("Here is") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("/*") || it.trim().startsWith("*") }
            .joinToString("\n")
            .trim()
    }
}
