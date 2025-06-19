package com.spr

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class JsonToDtoAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val file: VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (file != null && file.extension == "json") {
            val project = e.project
            if (project == null) return

            val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("JSON to DTO Generator")
            val progressNotification = notificationGroup.createNotification(
                "Generating DTOs...",
                "Please wait while DTOs are being generated",
                NotificationType.INFORMATION
            )
            progressNotification.notify(project)

            try {
                val jsonContent = String(file.contentsToByteArray())
                val dtoClasses = generateDtoFromJson(jsonContent, file.nameWithoutExtension)

                val outputDir = file.parent.path + "/DTOs"
                File(outputDir).mkdirs()
                
                val generatedFiles = AtomicInteger(0)
                val totalFiles = dtoClasses.size
                
                dtoClasses.forEach { (className, content) ->
                    val outputFile = File("$outputDir/$className.java")
                    outputFile.writeText(content)
                    generatedFiles.incrementAndGet()
                }

                if (generatedFiles.get() == totalFiles) {
                    progressNotification.expire()
                    val successNotification = notificationGroup.createNotification(
                        "DTOs Generated.",
                        "Generated DTOs in: $outputDir",
                        NotificationType.INFORMATION
                    )
                    successNotification.notify(project)
                } else {
                    progressNotification.expire()
                    val errorNotification = notificationGroup.createNotification(
                        "Error Generating DTOs",
                        "Some DTOs failed to generate. Expected: $totalFiles, Generated: ${generatedFiles.get()}",
                        NotificationType.ERROR
                    )
                    errorNotification.notify(project)
                }
            } catch (ex: Exception) {
                progressNotification.expire()
                val errorNotification = notificationGroup.createNotification(
                    "Error Generating DTOs",
                    "Failed to generate DTOs: ${ex.message}",
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

    private fun generateClassFromJsonObject(jsonObject: JsonObject, className: String, result: MutableMap<String, String>): String {
        val properties = mutableListOf<String>()
        val gettersAndSetters = mutableListOf<String>()
        
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
                        val firstElement = value.asJsonArray.get(0)
                        when {
                            firstElement.isJsonPrimitive -> getJavaType(firstElement.asJsonPrimitive)
                            firstElement.isJsonObject -> {
                                val nestedClassName = className + key.capitalize()
                                val nestedClass = generateClassFromJsonObject(firstElement.asJsonObject, nestedClassName, result)
                                result[nestedClassName + "Dto"] = nestedClass
                                nestedClassName + "Dto"
                            }
                            else -> "Object"
                        }
                    } else {
                        "Object"
                    }
                    "List<$arrayType>"
                }
                else -> "Object"
            }
            properties.add("    private $propertyType $propertyName;")
            
            // Add getter
            gettersAndSetters.add("""
                public $propertyType get${key.capitalize()}() {
                    return $propertyName;
                }
            """.trimIndent())
            
            // Add setter
            gettersAndSetters.add("""
                public void set${key.capitalize()}($propertyType $propertyName) {
                    this.$propertyName = $propertyName;
                }
            """.trimIndent())
        }

        val classContent = buildString {
            appendLine("public class ${className}Dto {")
            appendLine()
            // Add properties
            properties.forEach { appendLine(it) }
                    appendLine()
            // Add getters and setters
            gettersAndSetters.forEach { appendLine(it) }
            append("}")
        }

        return classContent
    }

    private fun getJavaType(primitive: JsonPrimitive): String {
        return when {
            primitive.isBoolean -> "Boolean"
            primitive.isNumber -> {
                val number = primitive.asNumber
                when {
                    number is Int -> "Integer"
                    number is Long -> "Long"
                    number is Double -> "Double"
                    number is Float -> "Float"
                    else -> "Number"
                }
            }
            primitive.isString -> "String"
            else -> "Object"
        }
    }

    private fun String.capitalize(): String {
        return this.replaceFirstChar { it.uppercase() }
    }

    private fun String.decapitalize(): String {
        return this.replaceFirstChar { it.lowercase() }
    }
}
