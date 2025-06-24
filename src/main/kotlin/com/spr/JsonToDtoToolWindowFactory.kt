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
        private val inputJsonDropdown = JComboBox<String>()
        private val outputJsonDropdown = JComboBox<String>()
        private val scenariosPanel = JPanel()
        private val scenariosScrollPane = JBScrollPane(scenariosPanel)
        private val selectedPairs = mutableListOf<Pair<String, String>>()
        private val statusLabel = JBLabel("Ready", SwingConstants.CENTER)
        private val refreshButton = JButton("Refresh")
        private val addScenarioButton = JButton("Add Scenario")
        private val generateButton = JButton("Generate Scenarios")
        private val executor = Executors.newSingleThreadScheduledExecutor()
        private var fileListForDropdown: List<String> = emptyList()

        init {
            scenariosPanel.layout = BoxLayout(scenariosPanel, BoxLayout.Y_AXIS)
            scenariosScrollPane.preferredSize = Dimension(400, 200)

            refreshButton.addActionListener { refreshBranches() }
            addScenarioButton.addActionListener { addScenario() }
            generateButton.addActionListener { generateScenarios() }
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
            formPanel.add(formRow("Input JSON:", inputJsonDropdown))
            formPanel.add(formRow("Output JSON:", outputJsonDropdown))
            formPanel.add(Box.createVerticalStrut(5))
            formPanel.add(centerRow(addScenarioButton))
            formPanel.add(Box.createVerticalStrut(10))
            formPanel.add(scenariosScrollPane)
            formPanel.add(Box.createVerticalStrut(10))
            formPanel.add(centerRow(generateButton))
            formPanel.add(Box.createVerticalStrut(10))
            formPanel.add(centerRow(statusLabel))

            return formPanel
        }

        private fun formRow(label: String, comboBox: JComboBox<String>): JPanel {
            return JPanel(BorderLayout(10, 0)).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 30)
                add(JBLabel(label), BorderLayout.WEST)
                add(comboBox, BorderLayout.CENTER)
            }
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

        private fun addScenario() {
            val input = inputJsonDropdown.selectedItem as? String
            val output = outputJsonDropdown.selectedItem as? String

            if (input != null && output != null) {
                if (input == output) {
                    statusLabel.text = "Input and Output files cannot be the same."
                    return
                }
                val pair = Pair(input, output)
                if (!selectedPairs.contains(pair)) {
                    selectedPairs.add(pair)
                    rebuildScenariosList()
                    statusLabel.text = "Scenario added."
                } else {
                    statusLabel.text = "This scenario pair already exists."
                }
            } else {
                statusLabel.text = "Please select both an input and an output file."
            }
        }

        private fun rebuildScenariosList() {
            scenariosPanel.removeAll()
            selectedPairs.forEachIndexed { index, pair ->
                val row = JPanel(BorderLayout()).apply {
                    maximumSize = Dimension(Int.MAX_VALUE, 30)
                    border = JBUI.Borders.empty(2)
                    add(JBLabel("${pair.first}  ->  ${pair.second}"), BorderLayout.CENTER)
                    val removeButton = JButton("Remove").apply {
                        addActionListener {
                            selectedPairs.removeAt(index)
                            rebuildScenariosList()
                        }
                    }
                    add(removeButton, BorderLayout.EAST)
                }
                scenariosPanel.add(row)
            }
            scenariosPanel.revalidate()
            scenariosPanel.repaint()
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
                        inputJsonDropdown.removeAllItems()
                        outputJsonDropdown.removeAllItems()
                        files.forEach {
                            inputJsonDropdown.addItem(it)
                            outputJsonDropdown.addItem(it)
                        }
                        selectedPairs.clear()
                        rebuildScenariosList()
                        statusLabel.text = "Ready"
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Error fetching JSON files: ${e.message}"
                    }
                }
            }
        }

        private fun generateScenarios() {
            if (selectedPairs.isEmpty()) {
                statusLabel.text = "Please add at least one scenario."
                return
            }

            statusLabel.text = "Generating scenarios..."
            val action = JsonToDtoAction()
            selectedPairs.forEach { pair ->
                val inputFile = project.baseDir.findFileByRelativePath(pair.first)
                val outputFile = project.baseDir.findFileByRelativePath(pair.second)

                if (inputFile != null && outputFile != null) {
                    action.generateScenario(project, inputFile, outputFile)
                }
            }
        }
    }
}
