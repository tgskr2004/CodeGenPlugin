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

    @Deprecated("Use generateServiceFromScenario instead", ReplaceWith("generateServiceFromScenario(...)"))
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

    fun generateServiceFromScenario(
        requestDtoSource: String,
        responseDtoSource: String,
        inputJsonContent: String,
        outputJsonContent: String
    ): LLMResult? {
        val prompt = """
            You are a senior Java backend developer tasked with reverse-engineering an API endpoint's logic.
            You are given a request DTO, a response DTO, an example JSON request, and an example JSON response.
            Your task is to generate a Java Service class that contains the business logic to transform the request DTO into the response DTO.

            Analyze the relationship between the request and response to infer the business logic. For example:
            - Did a field get copied or renamed?
            - Was a new ID or timestamp generated?
            - Was a calculation performed (e.g., summing a list of items to get a total)?
            - Was data transformed (e.g., combining a first and last name into a full name)?

            The generated service should:
            - Have a public method that accepts the request DTO and returns the response DTO.
            - Encapsulate the inferred business logic inside this method.
            - If necessary, include mockable dependencies (like a repository or a validator) that would be needed in a real-world application. Use interfaces for dependencies.
            - Use clean code principles and Java best practices.

            REQUEST DTO:
            ```java
            $requestDtoSource
            ```

            RESPONSE DTO:
            ```java
            $responseDtoSource
            ```

            EXAMPLE JSON REQUEST:
            ```json
            $inputJsonContent
            ```

            EXAMPLE JSON RESPONSE:
            ```json
            $outputJsonContent
            ```

            Now, generate the full Java Service class.
        """.trimIndent()

        return sendOpenAIRequest(prompt)
    }

    @Deprecated("Use generateTestsForScenario instead", ReplaceWith("generateTestsForScenario(...)"))
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

    fun generateTestsForScenario(
        requestDtoSource: String,
        responseDtoSource: String,
        serviceSource: String,
        inputJsonContent: String,
        outputJsonContent: String,
        testClassName: String
    ): LLMResult? {
        val prompt = """
            You are a senior Java test engineer. Your task is to write a complete, runnable JUnit 5 test for a Java service method.
            You are given the request DTO, the response DTO, the service class itself, and the original JSON files that define the scenario.

            Your generated test class must:
            1. Be a JUnit 5 test.
            2. Mock any dependencies the service has (e.g., repositories, validators) using Mockito.
            3. Contain a test method that follows the Arrange-Act-Assert pattern.
            4. **Arrange**:
               - Create an instance of the service, injecting the mocks.
               - Create an instance of the request DTO. Populate it with the exact data from the provided "EXAMPLE JSON REQUEST".
               - Set up mock behavior (e.g., `when(repository.findById(...)).thenReturn(...)`) if needed to make the test pass.
            5. **Act**:
               - Call the service method with the request DTO.
            6. **Assert**:
               - Assert that the fields in the response DTO returned by the service method **exactly match** the corresponding values in the provided "EXAMPLE JSON RESPONSE". Use multiple, specific `assertEquals` calls for each field. Do not just check for `notNull`.
            7. Use clear, descriptive test method names (e.g., `shouldCorrectlyTransformRequestToResponse_whenGivenExampleData`).
            8. The test class name should be `$testClassName`.

            REQUEST DTO:
            ```java
            $requestDtoSource
            ```

            RESPONSE DTO:
            ```java
            $responseDtoSource
            ```

            SERVICE CLASS:
            ```java
            $serviceSource
            ```

            EXAMPLE JSON REQUEST:
            ```json
            $inputJsonContent
            ```

            EXAMPLE JSON RESPONSE:
            ```json
            $outputJsonContent
            ```

            Now, generate the full JUnit 5 test class.
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
