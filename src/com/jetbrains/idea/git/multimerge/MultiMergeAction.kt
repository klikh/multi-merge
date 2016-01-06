package com.jetbrains.idea.git.multimerge

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.impl.ApplicationImpl
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.SpellCheckingEditorCustomizationProvider
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vfs.VfsUtil
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
import git4idea.commands.*
import git4idea.repo.GitRepository
import git4idea.reset.GitResetMode
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
    GitUtil.getRepositoryManager(project).repositories.forEach {
      branches.addAll(it.branches.localBranches.map {it.name})
      branches.addAll(it.branches.remoteBranches.map {it.name})
    }
    val props = showDialog(project, branches)
    if (props != null) {
      var success = false
      ProgressManager.getInstance().runProcessWithProgressSynchronously({
        success = merge(project, props.branches)
      }, "Merging...", true, project)
      if (success && props.make) {
        CompilerManager.getInstance(project).make{ aborted, errors, warnings, context ->
          if (!aborted && errors == 0 && props.quit) {
            quit()
          }
        }
      }
    }
  }

  private fun merge(project: Project, branches: List<String>): Boolean {
    val successful = arrayListOf<GitRepository>();
    val skipped = arrayListOf<GitRepository>();
    for (repository in GitUtil.getRepositoryManager(project).repositories) {
      val res = merge(branches, repository)
      if (res == SKIP_RESULT) {
        skipped.add(repository)
      }
      else if (!res.success()) {
        var choice = -1
        ApplicationManager.getApplication().invokeAndWait({
          choice = Messages.showYesNoDialog(project, res.errorOutputAsJoinedString + "\n\nDo you want to undo?",
                  "Merge Failed", "Undo", "Do nothing", Messages.getErrorIcon())
        }, ModalityState.defaultModalityState())
        if (choice == Messages.YES) {
          undo(repository, successful)
        }
        return false;
      }
      else {
        successful.add(repository)
      }
    }
    val notifier = VcsNotifier.getInstance(project)
    if (skipped.isEmpty()) {
      notifier.notifySuccess("Merged successfully")
    }
    else {
      notifier.notifySuccess("Merged successfully", "Skipped " + skipped.joinToString("\n", transform = {it.root.name}))
    }
    return true;
  }

  private fun undo(repository: GitRepository, successful: Collection<GitRepository>) {
    val git = ServiceManager.getService(Git::class.java)
    val result = GitCompoundResult(repository.project)
    result.append(repository, git.resetMerge(repository, null))
    for (repo in successful) {
      result.append(repo, git.reset(repo, GitResetMode.HARD, "HEAD^"))
      VfsUtil.markDirtyAndRefresh(false, true, false, repo.root)
    }
    val notifier = VcsNotifier.getInstance(repository.project)
    if (result.totalSuccess()) {
      notifier.notifySuccess("Rolled Back the merge")
    }
    else {
      notifier.notifyError("Rollback Failed", result.errorOutputWithReposIndication)
    }
  }

  private fun merge(branches: List<String>, repository: GitRepository): GitCommandResult {
    val git = ServiceManager.getService(Git::class.java)
    val handler = GitLineHandler(repository.project, repository.root, GitCommand.MERGE)
    handler.addParameters(branches)
    val unknownPathspec = GitSimpleEventDetector(GitSimpleEventDetector.Event.INVALID_REFERENCE)
    handler.addLineListener(unknownPathspec)
    val result = git.runCommand(handler)
    VfsUtil.markDirtyAndRefresh(false, true, false, repository.root)
    if (unknownPathspec.hasHappened()) return SKIP_RESULT
    return result
  }

  val SKIP_RESULT = GitCommandResult(true, 1, emptyList(), emptyList(), null)

  private fun quit() {
    (ApplicationManager.getApplication() as ApplicationImpl).exit(false, false, false, false)
  }

  private fun showDialog(project: Project, branches: Set<String>): Properties? {
    val properties = PropertiesComponent.getInstance()
    val msg = "Choose branches to merge?"
    val branchChooser = createTextField(project, branches, properties.getValue("git.multimerge.branches", "").split('\n'))
    val make = JBCheckBox("Make after merge?", properties.getBoolean("git.multimerge.make", true))
    val quit = JBCheckBox("Quit after make?", properties.getBoolean("git.multimerge.quit", true))

    val panel = JBUI.Panels.simplePanel();
    panel.add(JBLabel(msg), BorderLayout.NORTH)
    panel.add(branchChooser)
    val options = JBUI.Panels.simplePanel()
    options.add(make, BorderLayout.WEST)
    options.add(quit, BorderLayout.CENTER)
    panel.add(options, BorderLayout.SOUTH)
    val builder = DialogBuilder().centerPanel(panel).title("Git Fetch Duration Test")
    builder.setPreferredFocusComponent(branchChooser)
    val ok = builder.showAndGet()

    return if (ok) {
      properties.setValue("git.multimerge.make", make.isSelected)
      properties.setValue("git.multimerge.branches", branchChooser.text)
      properties.setValue("git.multimerge.quit", quit.isSelected)
      Properties(branchChooser.text.split('\n'), make.isSelected, quit.isSelected)
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
    textField.text = initialValues.joinToString("\n")
    MyCompletionProvider(values, false).apply(textField)
    return textField
  }

  private data class Properties(val branches: List<String>, val make: Boolean, val quit: Boolean)

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

