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
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class JsonToDtoAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val file: VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project ?: return

        if (file != null && file.extension == "json") {
            val notificationGroup = NotificationGroupManager.getInstance()
                .getNotificationGroup("JSON to DTO Generator")

            val progressNotification = notificationGroup.createNotification(
                "Generating DTOs...",
                "Please wait while DTOs, services, and tests are being generated.",
                NotificationType.INFORMATION
            )
            progressNotification.notify(project)

            try {
                val jsonContent = String(file.contentsToByteArray())
                val dtoClasses = generateDtoFromJson(jsonContent, file.nameWithoutExtension)

                val baseOutputDir = file.parent.path + "/DTOs"
                val serviceDir = "$baseOutputDir/Service"
                val testDir = "$baseOutputDir/Test"
                File(baseOutputDir).mkdirs()
                File(serviceDir).mkdirs()
                File(testDir).mkdirs()

                val generatedFiles = AtomicInteger(0)
                ApplicationManager.getApplication().executeOnPooledThread {
                    var totalPromptTokens = 0
                    var totalCompletionTokens = 0
                    var totalTokenCount = 0
                    dtoClasses.forEach { (dtoClassName, dtoSource) ->
                        try {
                            val dtoFile = File("$baseOutputDir/$dtoClassName.java")
                            dtoFile.writeText(dtoSource)
                            generatedFiles.incrementAndGet()

                            val dtoBaseName = dtoClassName.removeSuffix("Dto")
                            val serviceResult = OpenAIClient.generateServiceFromDto(dtoSource, dtoClassName)
                            if (serviceResult != null && serviceResult.content.isNotBlank()) {
                                totalPromptTokens += serviceResult.promptTokens
                                totalCompletionTokens += serviceResult.completionTokens
                                totalTokenCount += serviceResult.totalTokens

                                val serviceFile = File("$serviceDir/${dtoBaseName}Service.java")
                                serviceFile.writeText(serviceResult.content)

                                val testResult = OpenAIClient.generateTests(dtoClassName, dtoSource, serviceResult.content)
                                if (testResult != null && testResult.content.isNotBlank()) {
                                    totalPromptTokens += testResult.promptTokens
                                    totalCompletionTokens += testResult.completionTokens
                                    totalTokenCount += testResult.totalTokens

                                    val testFile = File("$testDir/${dtoBaseName}ServiceGeneratedTest.java")
                                    testFile.writeText(testResult.content)
                                }
                            }

                        } catch (ex: Exception) {
                            ex.printStackTrace()
                        }
                    }

                    progressNotification.expire()
                    println("✅ Total token usage: prompt=$totalPromptTokens, completion=$totalCompletionTokens, total=$totalTokenCount")
                    ApplicationManager.getApplication().invokeLater {
                        val finalNotification = notificationGroup.createNotification(
                            "DTO processing complete",
                            "Generated DTOs, services, and test files in: $baseOutputDir",
                            NotificationType.INFORMATION
                        )
                        finalNotification.notify(project)
                    }
                }

            } catch (ex: Exception) {
                progressNotification.expire()
                val errorNotification = notificationGroup.createNotification(
                    "Error Generating DTOs",
                    "Failed to generate files: ${ex.message}",
                    NotificationType.ERROR
                )
                errorNotification.notify(project)
            }
        }
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
