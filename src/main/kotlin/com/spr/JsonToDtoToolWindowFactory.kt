// Updated UI to list JSON files with checkboxes and 'Select All / Deselect All' options

package com.spr

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.*

class JsonToDtoToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = JsonToDtoToolWindow(project).getContent()
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(content, null, false))
    }

    class JsonToDtoToolWindow(private val project: Project) {
        private val branchComboBox = JComboBox<String>()
        private val fileListPanel = JPanel()
        private val fileScrollPane = JBScrollPane(fileListPanel)
        private val selectedFiles = mutableSetOf<String>()
        private val statusLabel = JBLabel("Ready", SwingConstants.CENTER)
        private val refreshButton = JButton("Refresh")
        private val generateButton = JButton("Generate DTOs")
        private val selectAllButton = JButton("Select All")
        private val deselectAllButton = JButton("Deselect All")
        private val executor = Executors.newSingleThreadScheduledExecutor()
        private var fileListForDropdown: List<String> = emptyList()

        init {
            fileListPanel.layout = BoxLayout(fileListPanel, BoxLayout.Y_AXIS)
            fileScrollPane.preferredSize = Dimension(400, 200)

            refreshButton.addActionListener { refreshBranches() }
            generateButton.addActionListener { generateSelectedDtos() }
            selectAllButton.addActionListener { updateAllSelections(true) }
            deselectAllButton.addActionListener { updateAllSelections(false) }
            branchComboBox.addActionListener { refreshJsonFiles() }

            refreshBranches()
        }

        fun getContent() = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(createFormPanel(), BorderLayout.CENTER)
        }

        private fun createFormPanel(): JPanel {
            val formPanel = JPanel()
            formPanel.layout = BoxLayout(formPanel, BoxLayout.Y_AXIS)

            formPanel.add(formRow("Target Branch:", branchComboBox, refreshButton))
            formPanel.add(Box.createVerticalStrut(10))
            formPanel.add(JBLabel("Select Files:"))
            formPanel.add(fileScrollPane)
            formPanel.add(Box.createVerticalStrut(5))
            formPanel.add(centerRow(selectAllButton, deselectAllButton))
            formPanel.add(Box.createVerticalStrut(10))
            formPanel.add(centerRow(generateButton))
            formPanel.add(Box.createVerticalStrut(10))
            formPanel.add(centerRow(statusLabel))

            return formPanel
        }

        private fun formRow(label: String, comboBox: JComboBox<String>, button: JButton): JPanel {
            return JPanel(BorderLayout(10, 0)).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 30)
                add(JBLabel(label), BorderLayout.WEST)
                add(comboBox, BorderLayout.CENTER)
                add(button, BorderLayout.EAST)
            }
        }

        private fun centerRow(vararg components: JComponent): JPanel {
            return JPanel().apply {
                layout = FlowLayout(FlowLayout.CENTER)
                components.forEach { add(it) }
            }
        }

        private fun updateAllSelections(select: Boolean) {
            fileListPanel.components.forEach {
                if (it is JPanel) {
                    val cb = it.components.lastOrNull() as? JCheckBox
                    val file = cb?.actionCommand
                    if (cb != null && file != null) {
                        cb.isSelected = select
                        if (select) selectedFiles.add(file) else selectedFiles.remove(file)
                    }
                }
            }
        }

        private fun refreshBranches() {
            executor.submit {
                try {
                    val process = ProcessBuilder("git", "branch", "--format=%(refname:short)")
                        .directory(project.basePath?.let { File(it) })
                        .start()
                    val branches = process.inputStream.bufferedReader().readLines()
                    process.waitFor(5, TimeUnit.SECONDS)

                    SwingUtilities.invokeLater {
                        branchComboBox.removeAllItems()
                        branches.forEach { branchComboBox.addItem(it) }
                        refreshJsonFiles()
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Error fetching branches: ${e.message}"
                    }
                }
            }
        }

        private fun refreshJsonFiles() {
            val selectedBranch = branchComboBox.selectedItem as? String ?: return
            statusLabel.text = "Fetching JSON files..."

            executor.submit {
                try {
                    ProcessBuilder("git", "fetch", "origin", selectedBranch)
                        .directory(project.basePath?.let { File(it) })
                        .start().waitFor(5, TimeUnit.SECONDS)

                    val process = ProcessBuilder("git", "diff", "--name-only", "origin/$selectedBranch")
                        .directory(project.basePath?.let { File(it) })
                        .start()
                    val files = process.inputStream.bufferedReader().readLines()
                        .filter { it.endsWith(".json", ignoreCase = true) }
                    process.waitFor(5, TimeUnit.SECONDS)

                    SwingUtilities.invokeLater {
                        fileListForDropdown = files
                        selectedFiles.clear()
                        rebuildFileList()
                        statusLabel.text = "Ready"
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Error fetching JSON files: ${e.message}"
                    }
                }
            }
        }

        private fun rebuildFileList() {
            fileListPanel.removeAll()
            for (file in fileListForDropdown) {
                val row = JPanel(BorderLayout()).apply {
                    maximumSize = Dimension(Int.MAX_VALUE, 30)
                    border = JBUI.Borders.empty(2)
                    add(JBLabel(file), BorderLayout.WEST)

                    val checkBox = JCheckBox().apply {
                        actionCommand = file
                        isSelected = false
                        addActionListener {
                            if (isSelected) selectedFiles.add(file) else selectedFiles.remove(file)
                        }
                    }
                    add(checkBox, BorderLayout.EAST)
                }
                fileListPanel.add(row)
            }
            fileListPanel.revalidate()
            fileListPanel.repaint()
        }
        private fun generateSelectedDtos() {
            if (selectedFiles.isEmpty()) {
                statusLabel.text = "Please select a JSON file"
                return
            }

            statusLabel.text = "Generating DTOs..."
            selectedFiles.forEach { path ->
                val file = project.baseDir.findFileByRelativePath(path)
                if (file != null) {
                    val action = JsonToDtoAction()
                    val event = AnActionEvent.createFromDataContext("JSON_TO_DTO", null) {
                        when (it) {
                            CommonDataKeys.PROJECT.name -> project
                            CommonDataKeys.VIRTUAL_FILE.name -> file
                            else -> null
                        }
                    }
                    action.actionPerformed(event)
                }
            }
            statusLabel.text = "DTOs generated successfully"
        }
    }
}
