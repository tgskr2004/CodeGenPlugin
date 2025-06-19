package com.spr

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class JsonToDtoProjectService(private val project: Project) {
    private var changeListener: JsonFileChangeListener? = null

    init {
        startListening()
    }

    private fun startListening() {
        changeListener = JsonFileChangeListener(project)
    }

    fun stopListening() {
        changeListener?.dispose()
        changeListener = null
    }
} 