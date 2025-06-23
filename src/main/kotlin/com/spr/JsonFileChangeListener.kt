package com.spr

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class JsonFileChangeListener(private val project: Project) {
    private val executor = Executors.newSingleThreadScheduledExecutor()
    init {
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    events.forEach { event ->
                        val file = event.file
                        if (file != null && file.extension?.equals("json", ignoreCase = true) == true) {
                            checkForJsonChanges()
                        }
                        
                    }
                }
            }
        )
        checkForJsonChanges()
    }

    private fun checkForJsonChanges() {
        executor.schedule({
            try {
                val processBuilder = ProcessBuilder("git", "diff", "--name-only", "HEAD~1", "HEAD")
                processBuilder.directory(project.baseDir.toNioPath().toFile())
                processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val jsonFiles = mutableListOf<String>()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line?.endsWith(".json", ignoreCase = true) == true) {
                        jsonFiles.add(line)
                    }
                }
                process.waitFor(5, TimeUnit.SECONDS)

                jsonFiles.forEach { jsonFilePath ->
                    val jsonFile = project.baseDir.findFileByRelativePath(jsonFilePath)
                    if (jsonFile != null) {
                        convertJsonToDto(jsonFile)
                    }
                }
            } catch (e: Exception) {
                println("Error checking for JSON changes: ${e.message}")
            }
        }, 1, TimeUnit.SECONDS)
    }

    private fun convertJsonToDto(jsonFile: VirtualFile) {
        val action = JsonToDtoAction()
        val event = AnActionEvent.createFromDataContext(
            "JSON_TO_DTO",
            null,
            { dataId ->
                when (dataId) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.VIRTUAL_FILE.name -> jsonFile
                    else -> null
                }
            }
        )
        action.actionPerformed(event)
    }

    fun dispose() {
        executor.shutdown()
    }
} 