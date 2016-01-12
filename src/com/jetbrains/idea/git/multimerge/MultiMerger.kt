package com.jetbrains.idea.git.multimerge

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.impl.ApplicationImpl
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vfs.VfsUtil
import git4idea.GitPlatformFacade
import git4idea.GitUtil
import git4idea.commands.*
import git4idea.config.GitVcsSettings.UpdateChangesPolicy.SHELVE
import git4idea.repo.GitRepository
import git4idea.util.GitPreservingProcess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class Properties(val branches: List<String>, val make: Boolean, val quit: Boolean,
                               val checkoutBack: Boolean, val deleteTempBranch: Boolean)

internal class MultiMerger(val project: Project,
                           val repositories: List<GitRepository>,
                           val tempBranchName: String,
                           val originalBranches: Map<GitRepository, String>,
                           val props: Properties) {

  private val git = ServiceManager.getService(Git::class.java)

  fun run(indicator: ProgressIndicator): Unit {
    val facade = ServiceManager.getService(project, GitPlatformFacade::class.java)
    val roots = GitUtil.getRootsFromRepositories(repositories)
    var success = false
    GitPreservingProcess(project, facade, git, roots, "merge", "name", SHELVE, indicator) {
      success = checkoutTempBranch()
      if (success) {
        success = merge()
      }
    }.execute()

    if (!success || !props.make) return;

    var makeSuccess = false
    val waiter = CountDownLatch(1)
    ApplicationManager.getApplication().invokeAndWait({
      CompilerManager.getInstance(project).make { aborted, errors, warnings, context ->
        makeSuccess = !aborted && errors == 0
        waiter.countDown()
      }
    }, ModalityState.defaultModalityState())


    while (!waiter.await(100, TimeUnit.MILLISECONDS)) {
      indicator.checkCanceled()
    }
    if (makeSuccess) {
      if (props.checkoutBack) {
        makeSuccess = undoCheckout(repositories, props.deleteTempBranch)
      }
      if (makeSuccess && props.quit) {
        quit()
      }
    }
  }

  private fun checkoutTempBranch(): Boolean {
    val successful = arrayListOf<GitRepository>();
    repositories.forEach {
      val res = git.checkoutNewBranch(it, tempBranchName, null);
      if (!res.success()) {
        undoIfNeeded("Checkout Failed", res, successful) {
          undoCheckout(successful, true)
        }
        return false;
      } else {
        successful.add(it)
      }
    }
    return true;
  }

  private fun merge(): Boolean {
    val successful = arrayListOf<GitRepository>();
    val skipped = arrayListOf<GitRepository>();
    for (repository in repositories) {
      val existingBranches = props.branches.filter { repository.branches.findBranchByName(it) != null }
      if (existingBranches.isEmpty()) {
        skipped.add(repository)
        successful.add(repository)
      } else {
        val res = merge(existingBranches, repository)
        if (!res.success()) {
          undoIfNeeded("Merge Failed", res, successful) {
            if (undoMerge(repository)) {
              undoCheckout(repositories, true)
            }
          }
          return false;
        } else {
          successful.add(repository)
        }
      }
    }
    val notifier = VcsNotifier.getInstance(project)
    if (skipped.isEmpty()) {
      notifier.notifySuccess("Merged successfully")
    } else {
      notifier.notifySuccess("Merged successfully", "Skipped " + skipped.joinToString("\n", transform = { it.root.name }))
    }
    return true;
  }

  private fun undoIfNeeded(title: String,
                           res: GitCommandResult,
                           toUndo: List<GitRepository>,
                           undoOperation: () -> Unit) {
    if (!toUndo.isEmpty()) {
      var choice = -1
      ApplicationManager.getApplication().invokeAndWait({
        choice = Messages.showYesNoDialog(project, res.errorOutputAsJoinedString + "\n\nDo you want to undo?",
                title, "Undo", "Do nothing", Messages.getErrorIcon())
      }, ModalityState.defaultModalityState())

      if (choice == Messages.YES) {
        undoOperation()
      }
    }
  }

  private fun undoCheckout(successful: Collection<GitRepository>, deleteTempBranch: Boolean) : Boolean{
    val result = GitCompoundResult(project)
    successful.forEach {
      val checkoutRes = git.checkout(it, originalBranches[it]!!, null, true, false)
      result.append(it, checkoutRes)
      if (deleteTempBranch && checkoutRes.success()) {
        result.append(it, git.branchDelete(it, tempBranchName, true))
      }
      VfsUtil.markDirtyAndRefresh(false, true, false, it.root)
    }
    val notifier = VcsNotifier.getInstance(project)
    if (result.totalSuccess()) {
      notifier.notifySuccess("Rolled back the checkout")
      return true
    } else {
      notifier.notifyError("Rollback Failed", result.errorOutputWithReposIndication)
      return false
    }
  }

  private fun undoMerge(repository: GitRepository) : Boolean {
    val resetMergeResult = git.resetMerge(repository, null)
    if (!resetMergeResult.success()) {
      VcsNotifier.getInstance(repository.project).notifyError("Rollback Failed", resetMergeResult.errorOutputAsHtmlString)
      return false;
    }
    VfsUtil.markDirtyAndRefresh(false, true, false, repository.root)
    return true;
  }

  private fun merge(branches: List<String>, repository: GitRepository): GitCommandResult {
    val git = ServiceManager.getService(Git::class.java)
    val handler = GitLineHandler(repository.project, repository.root, GitCommand.MERGE)
    handler.addParameters(branches)
    val result = git.runCommand(handler)
    VfsUtil.markDirtyAndRefresh(false, true, false, repository.root)
    return result
  }

  private fun quit() {
    (ApplicationManager.getApplication() as ApplicationImpl).exit(false, false, false, false)
  }
}
