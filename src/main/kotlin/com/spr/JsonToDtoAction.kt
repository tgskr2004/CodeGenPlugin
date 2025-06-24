package com.spr

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class JsonToDtoAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JSON to DTO Generator")
            .createNotification(
                "Info",
                "Please use the 'JSON to DTO' tool window to generate scenarios.",
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    fun generateScenario(project: Project, inputFile: VirtualFile, outputFile: VirtualFile) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("JSON to DTO Generator")

        val scenarioName = findCommonPrefix(inputFile.nameWithoutExtension, outputFile.nameWithoutExtension)
            .let { it.ifBlank { "Scenario" } }
            .replace("-", " ").replace("_", " ")
            .split(" ").joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }


        val progressNotification = notificationGroup.createNotification(
            "Generating Scenario: $scenarioName",
            "Please wait while DTOs, services, and tests are being generated.",
            NotificationType.INFORMATION
        )
        progressNotification.notify(project)

        try {
            val inputJsonContent = String(inputFile.contentsToByteArray())
            val requestDtoName = "${scenarioName}Request"
            val requestDtoClasses = generateDtoFromJson(inputJsonContent, requestDtoName)

            val outputJsonContent = String(outputFile.contentsToByteArray())
            val responseDtoName = "${scenarioName}Response"
            val responseDtoClasses = generateDtoFromJson(outputJsonContent, responseDtoName)

            val allDtoClasses = requestDtoClasses + responseDtoClasses

            val baseOutputDir = inputFile.parent.path + "/${scenarioName}Scenario"
            val dtoDir = "$baseOutputDir/DTOs"
            val serviceDir = "$baseOutputDir/Service"
            val testDir = "$baseOutputDir/Test"
            File(dtoDir).mkdirs()
            File(serviceDir).mkdirs()
            File(testDir).mkdirs()

            ApplicationManager.getApplication().executeOnPooledThread {
                var totalPromptTokens = 0
                var totalCompletionTokens = 0
                var totalTokenCount = 0

                allDtoClasses.forEach { (dtoClassName, dtoSource) ->
                    try {
                        val dtoFile = File("$dtoDir/$dtoClassName.java")
                        dtoFile.writeText(dtoSource)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }

                val requestDtoSource = requestDtoClasses["${requestDtoName}Dto"] ?: ""
                val responseDtoSource = responseDtoClasses["${responseDtoName}Dto"] ?: ""

                val serviceResult = OpenAIClient.generateServiceFromScenario(
                    requestDtoSource = requestDtoSource,
                    responseDtoSource = responseDtoSource,
                    inputJsonContent = inputJsonContent,
                    outputJsonContent = outputJsonContent
                )
                if (serviceResult != null && serviceResult.content.isNotBlank()) {
                    totalPromptTokens += serviceResult.promptTokens
                    totalCompletionTokens += serviceResult.completionTokens
                    totalTokenCount += serviceResult.totalTokens

                    val serviceName = "${scenarioName}Service"
                    val serviceFile = File("$serviceDir/${serviceName}.java")
                    serviceFile.writeText(serviceResult.content)

                    val testClassName = "${serviceName}GeneratedTest"
                    val testResult = OpenAIClient.generateTestsForScenario(
                        requestDtoSource = requestDtoSource,
                        responseDtoSource = responseDtoSource,
                        serviceSource = serviceResult.content,
                        inputJsonContent = inputJsonContent,
                        outputJsonContent = outputJsonContent,
                        testClassName = testClassName
                    )
                    if (testResult != null && testResult.content.isNotBlank()) {
                        totalPromptTokens += testResult.promptTokens
                        totalCompletionTokens += testResult.completionTokens
                        totalTokenCount += testResult.totalTokens

                        val testFile = File("$testDir/$testClassName.java")
                        testFile.writeText(testResult.content)
                    }
                }

                progressNotification.expire()
                println("✅ Total token usage for scenario '$scenarioName': prompt=$totalPromptTokens, completion=$totalCompletionTokens, total=$totalTokenCount")
                ApplicationManager.getApplication().invokeLater {
                    val finalNotification = notificationGroup.createNotification(
                        "Scenario Generation Complete: $scenarioName",
                        "Generated files in: $baseOutputDir",
                        NotificationType.INFORMATION
                    )
                    finalNotification.notify(project)
                }
            }

        } catch (ex: Exception) {
            progressNotification.expire()
            val errorNotification = notificationGroup.createNotification(
                "Error Generating Scenario",
                "Failed to generate files for scenario '$scenarioName': ${ex.message}",
                NotificationType.ERROR
            )
            errorNotification.notify(project)
        }
    }

    private fun findCommonPrefix(s1: String, s2: String): String {
        val minLength = minOf(s1.length, s2.length)
        for (i in 0 until minLength) {
            if (s1[i] != s2[i]) {
                return s1.substring(0, i)
            }
        }
        return s1.substring(0, minLength)
    }

    private fun generateDtoFromJson(json: String, rootClassName: String): Map<String, String> {
        val gson = Gson()
        val jsonElement = gson.fromJson(json, JsonElement::class.java)
        val result = mutableMapOf<String, String>()

        if (jsonElement.isJsonObject) {
            val mainClass = generateClassFromJsonObject(jsonElement.asJsonObject, rootClassName, result)
            result[rootClassName + "Dto"] = mainClass
        }

        return result
    }

    private fun generateClassFromJsonObject(
        jsonObject: JsonObject,
        className: String,
        result: MutableMap<String, String>
    ): String {
        val properties = mutableListOf<String>()
        val methods = mutableListOf<String>()

        jsonObject.entrySet().forEach { (key, value) ->
            val propertyName = key.decapitalize()
            val propertyType = when {
                value.isJsonPrimitive -> getJavaType(value.asJsonPrimitive)
                value.isJsonObject -> {
                    val nestedClassName = className + key.capitalize()
                    val nestedClass = generateClassFromJsonObject(value.asJsonObject, nestedClassName, result)
                    result[nestedClassName + "Dto"] = nestedClass
                    nestedClassName + "Dto"
                }
                value.isJsonArray -> {
                    val arrayType = if (value.asJsonArray.size() > 0) {
                        val first = value.asJsonArray[0]
                        when {
                            first.isJsonPrimitive -> getJavaType(first.asJsonPrimitive)
                            first.isJsonObject -> {
                                val nestedClassName = className + key.capitalize()
                                val nestedClass = generateClassFromJsonObject(first.asJsonObject, nestedClassName, result)
                                result[nestedClassName + "Dto"] = nestedClass
                                nestedClassName + "Dto"
                            }
                            else -> "Object"
                        }
                    } else "Object"
                    "List<$arrayType>"
                }
                else -> "Object"
            }

            properties.add("    private $propertyType $propertyName;")

            methods.add("""
                public $propertyType get${key.capitalize()}() {
                    return $propertyName;
                }
            """.trimIndent())

            methods.add("""
                public void set${key.capitalize()}($propertyType $propertyName) {
                    this.$propertyName = $propertyName;
                }
            """.trimIndent())
        }

        return buildString {
            appendLine("public class ${className}Dto {")
            appendLine()
            properties.forEach { appendLine(it) }
            appendLine()
            methods.forEach { appendLine(it) }
            appendLine("}")
        }
    }

    private fun getJavaType(primitive: JsonPrimitive): String {
        return when {
            primitive.isBoolean -> "Boolean"
            primitive.isNumber -> "Double"
            primitive.isString -> "String"
            else -> "Object"
        }
    }

    private fun String.capitalize(): String = replaceFirstChar { it.uppercase() }
    private fun String.decapitalize(): String = replaceFirstChar { it.lowercase() }
}
