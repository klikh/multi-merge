package com.jetbrains.idea.git.multimerge

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.SpellCheckingEditorCustomizationProvider
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.ui.EditorCustomization
import com.intellij.ui.EditorTextField
import com.intellij.ui.EditorTextFieldProvider
import com.intellij.ui.SoftWrapsEditorCustomization
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.TextFieldCompletionProviderDumbAware
import com.intellij.util.containers.ContainerUtil
import com.intellij.util.ui.JBUI
import git4idea.GitUtil
import git4idea.repo.GitRepository
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.border.CompoundBorder

class MultiMergeAction : DumbAwareAction() {

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(event: AnActionEvent) {
    val project = event.project!!
    val branches = hashSetOf<String>()
    val repositories = GitUtil.getRepositoryManager(project).repositories
    repositories.forEach {
      branches.addAll(it.branches.localBranches.map { it.name })
      branches.addAll(it.branches.remoteBranches.map { it.name })
    }
    val props = showDialog(project, branches)
    if (props != null) {
      val tempBranchName = findTempBranchName(repositories)
      val originalBranches = recordOriginalBranches(repositories)

      val multiMerger = MultiMerger(project, repositories, tempBranchName, originalBranches, props)
      ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Performing Multi Merge...") {
        override fun run(pi: ProgressIndicator) {
          multiMerger.run(pi)
        }
      })
    }
  }

  private fun recordOriginalBranches(repositories: List<GitRepository>): Map<GitRepository, String> {
    return repositories.toMap { Pair(it, it.currentBranchName!!) }
  }

  private fun findTempBranchName(repositories: Collection<GitRepository>): String {
    val baseName = "multi-merge"
    var name = baseName
    var step = 1;
    while (repositories.find { it.branches.findBranchByName(name) != null } != null) {
      name = baseName + step.toString()
      step++
    }
    return name
  }

  private fun showDialog(project: Project, branches: Set<String>): Properties? {
    val properties = PropertiesComponent.getInstance()
    val msg = "Choose branches to merge"
    var savedBranches = properties.getValue("git.multimerge.branches", "").split('\n').map { it.trim() }
    if (savedBranches.isEmpty()) savedBranches = listOf("master")
    val branchChooser = createTextField(project, branches, savedBranches)
    val make = JBCheckBox("Make after merge", properties.getBoolean("git.multimerge.make", true))
    val rollback = JBCheckBox("Rollback after make", properties.getBoolean("git.multimerge.rollback", true))
    rollback.toolTipText = "Checkout the original branch and delete the temporary branch.\n" +
            "Not that if you won't exit immediately, and if you have 'compile project automatically' enabled," +
            "the build information will be overridden by original branch";
    val deleteTempBranch = JBCheckBox("Delete temporary branch", properties.getBoolean("git.multimerge.rollback.delete", true))
    val quit = JBCheckBox("Quit after make", properties.getBoolean("git.multimerge.quit", true))


    val options1 = JBUI.Panels.simplePanel()
    options1.add(make, BorderLayout.WEST)
    options1.add(quit, BorderLayout.CENTER)
    val options2 = JBUI.Panels.simplePanel()
    options2.add(rollback, BorderLayout.WEST)
    options2.add(deleteTempBranch, BorderLayout.CENTER)
    val options = JBUI.Panels.simplePanel()
    options.add(options1, BorderLayout.NORTH)
    options.add(options2, BorderLayout.CENTER)

    val panel = JBUI.Panels.simplePanel();
    panel.add(JBLabel(msg), BorderLayout.NORTH)
    panel.add(branchChooser)
    panel.add(options, BorderLayout.SOUTH)
    val builder = DialogBuilder().centerPanel(panel).title("Git Fetch Duration Test")
    builder.setPreferredFocusComponent(branchChooser)
    val ok = builder.showAndGet()

    return if (ok) {
      properties.setValue("git.multimerge.make", make.isSelected, true)
      properties.setValue("git.multimerge.branches", branchChooser.text)
      properties.setValue("git.multimerge.quit", quit.isSelected, true)
      properties.setValue("git.multimerge.rollback", rollback.isSelected, true)
      properties.setValue("git.multimerge.rollback.delete", deleteTempBranch.isSelected, true)
      Properties(branchChooser.text.split('\n').map { it.trim() },
              make.isSelected, quit.isSelected, rollback.isSelected, deleteTempBranch.isSelected)
    } else null;
  }

  private fun createTextField(project: Project, values: Set<String>, initialValues: List<String>): EditorTextField {
    val service = ServiceManager.getService(project, EditorTextFieldProvider::class.java)
    val features = ContainerUtil.packNullables<EditorCustomization>(SoftWrapsEditorCustomization.ENABLED,
            SpellCheckingEditorCustomizationProvider.getInstance().disabledCustomization)
    val textField = service.getEditorField(FileTypes.PLAIN_TEXT.language, project, features)
    textField.border = CompoundBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2), textField.border)
    textField.setOneLineMode(false)
    textField.minimumSize = Dimension(200, 200)
    MyCompletionProvider(values, false).apply(textField, initialValues.joinToString("\n"))
    return textField
  }

  private class MyCompletionProvider internal constructor(private val myValues: Collection<String>, private val mySupportsNegativeValues: Boolean) : TextFieldCompletionProviderDumbAware(true) {

    override fun getPrefix(currentTextPrefix: String): String {
      val separatorPosition = lastSeparatorPosition(currentTextPrefix)
      val prefix = if (separatorPosition == -1) currentTextPrefix else currentTextPrefix.substring(separatorPosition + 1).trim { it <= ' ' }
      return if (mySupportsNegativeValues && prefix.startsWith("-")) prefix.substring(1) else prefix
    }

    private fun lastSeparatorPosition(text: String): Int {
      var lastPosition = -1
      for (separator in listOf('\n')) {
        val lio = text.lastIndexOf(separator)
        if (lio > lastPosition) {
          lastPosition = lio
        }
      }
      return lastPosition
    }

    @SuppressWarnings("StringToUpperCaseOrToLowerCaseWithoutLocale")
    override fun addCompletionVariants(text: String, offset: Int, prefix: String,
                                       result: CompletionResultSet) {
      result.addLookupAdvertisement("Select one or more users separated with comma, | or new lines")
      for (completionVariant in myValues) {
        val element = LookupElementBuilder.create(completionVariant)
        result.addElement(element.withLookupString(completionVariant.toLowerCase()))
      }
    }
  }
}

