// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.devkit.actions.updateFromSources

import com.intellij.CommonBundle
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.impl.ApplicationImpl
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.systemIndependentPath
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import com.intellij.task.ProjectTaskNotification
import com.intellij.task.ProjectTaskResult
import com.intellij.util.SystemProperties
import org.jetbrains.idea.devkit.util.PsiUtil
import java.io.File
import java.util.*
import kotlin.collections.LinkedHashSet

private val LOG: Logger = logger(::LOG)

open class UpdateIdeFromSourcesAction
 @JvmOverloads constructor(private val forceShowSettings: Boolean = false)
  : AnAction(if (forceShowSettings) "Update IDE from Sources Settings..." else "Update IDE from Sources...",
             "Builds an installation of IntelliJ IDEA from the currently opened sources replace the current installation by its.", null) {


  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    if (forceShowSettings || UpdateFromSourcesSettings.getState().showSettings) {
      val ok = UpdateFromSourcesDialog(project, forceShowSettings).showAndGet()
      if (!ok) return
    }

    fun error(message: String) {
      Messages.showErrorDialog(project, message, CommonBundle.getErrorTitle())
    }

    val state = UpdateFromSourcesSettings.getState()
    val devIdeaHome = project.basePath ?: return
    val workIdeHome = state.workIdePath ?: PathManager.getHomePath()
    if (!ApplicationManager.getApplication().isRestartCapable && FileUtil.pathsEqual(workIdeHome, PathManager.getHomePath())) {
      return error("This IDE cannot restart itself so updating from sources isn't supported")
    }

    val notIdeHomeMessage = checkIdeHome(workIdeHome)
    if (notIdeHomeMessage != null) {
      return error("$workIdeHome is not a valid IDE home: $notIdeHomeMessage")
    }

    val scriptFile = File(devIdeaHome, "build/scripts/idea_ultimate.gant")
    if (!scriptFile.exists()) {
      return error("$scriptFile doesn't exist")
    }
    if (!scriptFile.readText().contains(includeBinAndRuntimeProperty)) {
      return error("The build scripts is out-of-date, please update to the latest 'master' sources.")
    }

    val bundledPluginDirsToSkip = if (!state.buildDisabledPlugins) {
      val pluginDirectoriesToSkip = LinkedHashSet<String>(state.pluginDirectoriesForDisabledPlugins)
      val allPlugins = PluginManagerCore.getPlugins()
      pluginDirectoriesToSkip.removeAll(allPlugins.filter { it.isBundled && it.isEnabled }.map { it.path }.filter { it.isDirectory }.map { it.name })
      allPlugins.filter { it.isBundled && !it.isEnabled }.map { it.path }.filter { it.isDirectory }.mapTo(pluginDirectoriesToSkip) { it.name }
      val list = pluginDirectoriesToSkip.toMutableList()
      state.pluginDirectoriesForDisabledPlugins = list
      list
    }
    else {
      emptyList<String>()
    }

    val deployDir = "$devIdeaHome/out/deploy"
    val backupDir = "$devIdeaHome/out/backup-before-update-from-sources"
    val params = createScriptJavaParameters(devIdeaHome, project, deployDir, scriptFile, bundledPluginDirsToSkip) ?: return
    ProjectTaskManager.getInstance(project).buildAllModules(object : ProjectTaskNotification {
      override fun finished(context: ProjectTaskContext, executionResult: ProjectTaskResult) {
        if (!executionResult.isAborted && executionResult.errors == 0) {
          runUpdateScript(params, project, workIdeHome, deployDir, backupDir)
        }
      }
    })
  }

  private fun checkIdeHome(workIdeHome: String): String? {
    val homeDir = File(workIdeHome)
    if (!homeDir.exists()) return null

    if (homeDir.isFile) return "it is not a directory"
    for (name in listOf("bin", "build.txt")) {
      if (!File(homeDir, name).exists()) {
        return "'$name' doesn't exist"
      }
    }
    return null
  }

  private fun runUpdateScript(params: JavaParameters,
                              project: Project,
                              workIdeHome: String,
                              deployDir: String,
                              backupDir: String) {
    object : Task.Backgroundable(project, "Updating from Sources", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = "Updating IDE from sources..."
        backupImportantFilesIfNeeded(workIdeHome, backupDir, indicator)
        indicator.text2 = "Deleting $deployDir"
        FileUtil.delete(File(deployDir))
        indicator.text2 = "Starting gant script"
        val scriptHandler = params.createOSProcessHandler()
        val errorLines = Collections.synchronizedList(ArrayList<String>())
        scriptHandler.addProcessListener(object : ProcessAdapter() {
          override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            LOG.debug("script: ${event.text}")
            if (outputType == ProcessOutputTypes.STDERR) {
              errorLines.add(event.text)
            }
            else if (outputType == ProcessOutputTypes.STDOUT) {
              indicator.text2 = event.text
            }
          }

          override fun processTerminated(event: ProcessEvent) {
            if (indicator.isCanceled) {
              return
            }

            if (event.exitCode != 0) {
              val errorText = errorLines.joinToString("\n")
              Notification("Update from Sources", "Update from Sources Failed", "Build script finished with ${event.exitCode}: $errorText",
                           NotificationType.ERROR).notify(project)
              return
            }

            if (!FileUtil.pathsEqual(workIdeHome, PathManager.getHomePath())) {
              startCopyingFiles(deployDir, workIdeHome, project)
              return
            }

            val command = generateUpdateCommand(deployDir, workIdeHome)
            if (indicator.isShowing) {
              restartWithCommand(command)
            }
            else {
              val notification = Notification("Update from Sources", "Update from Sources", "New installation is prepared from sources. <a href=\"#\">Restart</a>?",
                                              NotificationType.INFORMATION) { _, _ ->
                restartWithCommand(command)
              }
              Notifications.Bus.notify(notification, project)
            }
          }
        })
        scriptHandler.startNotify()
        while (!scriptHandler.isProcessTerminated) {
          scriptHandler.waitFor(300)
          indicator.checkCanceled()
        }
      }
    }.queue()
  }

  private fun backupImportantFilesIfNeeded(workIdeHome: String,
                                           backupDirPath: String,
                                           indicator: ProgressIndicator) {
    val backupDir = File(backupDirPath)
    if (backupDir.exists()) {
      LOG.debug("$backupDir already exists, skipping backup")
      return
    }

    LOG.debug("Backing up files from $workIdeHome to $backupDir")
    indicator.text2 = "Backing up files"
    FileUtil.createDirectory(backupDir)
    File(workIdeHome, "bin").listFiles()
      ?.filter { it.name !in safeToDeleteFilesInBin && it.extension !in safeToDeleteExtensions }
      ?.forEach { FileUtil.copy(it, File(backupDir, "bin/${it.name}")) }

    File(workIdeHome).listFiles()
      ?.filter { it.name !in safeToDeleteFilesInHome }
      ?.forEach { FileUtil.copyFileOrDir(it, File(backupDir, it.name)) }
  }

  private fun startCopyingFiles(deployDir: String, workIdeHome: String, project: Project) {
    object : Task.Backgroundable(project, "Updating from Sources", true) {
      override fun run(indicator: ProgressIndicator) {
        indicator.text = "Copying files to IDE distribution..."
        indicator.text2 = "Deleting old files"
        FileUtil.delete(File(workIdeHome))
        indicator.checkCanceled()
        indicator.text2 = "Copying new files"
        FileUtil.copyDir(File(deployDir), File(workIdeHome))
        indicator.checkCanceled()
        Notification("Update from Sources", "Update from Sources", "New installation is prepared at $workIdeHome.",
                     NotificationType.INFORMATION).notify(project)
      }
    }.queue()
  }

  private fun generateUpdateCommand(deployDir: String, workIdeHome: String): Array<String> {
    if (SystemInfo.isWindows) {
      val command = arrayOf(
        "RMDIR /Q /S \"${File(workIdeHome).absolutePath}\"",
        "XCOPY \"${File(deployDir).absolutePath}\" \"${File(workIdeHome).absolutePath}\\\" /Q /E /Y"
      )
      return arrayOf("cmd", "/C", command.joinToString(" && "))
    }
    val command = arrayOf(
      "rm -rf \"$workIdeHome\"/*",
      "cp -r \"$deployDir\"/* \"$workIdeHome\""
    )
    return arrayOf("/bin/sh", "-c", command.joinToString(" && "))
  }

  private fun restartWithCommand(command: Array<String>) {
    val application = ApplicationManager.getApplication() as ApplicationImpl
    application.invokeLater { application.exit(true, true, true, command) }
  }

  private fun createScriptJavaParameters(devIdeaHome: String,
                                         project: Project,
                                         deployDir: String,
                                         scriptFile: File,
                                         bundledPluginDirsToSkip: List<String>): JavaParameters? {
    val sdk = ProjectRootManager.getInstance(project).projectSdk
    if (sdk == null) {
      LOG.warn("Project SDK is not defined")
      return null
    }
    val params = JavaParameters()
    params.isUseClasspathJar = true
    params.setDefaultCharset(project)
    params.jdk = sdk
    //todo use org.jetbrains.idea.maven.utils.MavenUtil.resolveLocalRepository instead
    val m2Repo = File(SystemProperties.getUserHome(), ".m2/repository").systemIndependentPath

    //todo get from project configuration
    val coreClassPath = listOf(
      "$m2Repo/org/codehaus/groovy/groovy-all/2.4.17/groovy-all-2.4.17.jar",
      "$m2Repo/commons-cli/commons-cli/1.2/commons-cli-1.2.jar",
      "$devIdeaHome/community/lib/ant/lib/ant.jar",
      "$devIdeaHome/community/lib/ant/lib/ant-launcher.jar"
    )
    params.classPath.addAll(coreClassPath)

    params.mainClass = "org.codehaus.groovy.tools.GroovyStarter"
    params.programParametersList.add("--classpath")
    val buildScriptsModuleName = "intellij.idea.ultimate.build"
    val buildScriptsModule = ModuleManager.getInstance(project).findModuleByName(buildScriptsModuleName)
    if (buildScriptsModule == null) {
      LOG.warn("Build scripts module $buildScriptsModuleName is not found in the project")
      return null
    }
    val classpath = OrderEnumerator.orderEntries(buildScriptsModule)
      .recursively().withoutSdk().runtimeOnly().productionOnly().classes().pathsList
    coreClassPath.forEach { classpath.remove(FileUtil.toSystemDependentName(it)) }
    params.programParametersList.add(classpath.pathsString)
    params.programParametersList.add("--main")
    params.programParametersList.add("gant.Gant")
    params.programParametersList.add("--file")
    params.programParametersList.add(scriptFile.absolutePath)
    params.programParametersList.add("update-from-sources")
    params.vmParametersList.add("-D$includeBinAndRuntimeProperty=true")
    if (bundledPluginDirsToSkip.isNotEmpty()) {
      params.vmParametersList.add("-Dintellij.build.bundled.plugin.dirs.to.skip=${bundledPluginDirsToSkip.joinToString(",")}")
    }
    params.vmParametersList.add("-Ddeploy=$deployDir")
    params.vmParametersList.add("-DdevIdeaHome=$devIdeaHome")
    return params
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = PsiUtil.isIdeaProject(e.project)
  }
}

private const val includeBinAndRuntimeProperty = "intellij.build.generate.bin.and.runtime.for.unpacked.dist"
class UpdateIdeFromSourcesSettingsAction : UpdateIdeFromSourcesAction(true)

private val safeToDeleteFilesInHome = setOf(
  "bin", "help", "jre", "jre64", "jbr", "lib", "license", "plugins", "redist", "MacOS", "Resources",
  "build.txt", "product-info.json", "Install-Linux-tar.txt", "Install-Windows-zip.txt", "ipr.reg"
)

private val safeToDeleteFilesInBin = setOf(
  "append.bat", "appletviewer.policy", "format.sh", "format.bat",
  "fsnotifier", "fsnotifier-arm", "fsnotifier64",
  "inspect.bat", "inspect.sh",
  "restarter"
  /*
  "idea.properties",
  "idea.sh",
  "idea.bat",
  "idea.exe.vmoptions",
  "idea64.exe.vmoptions",
  "idea.vmoptions",
  "idea64.vmoptions",
  "log.xml",
*/
  )

private val safeToDeleteExtensions = setOf("exe", "dll", "dylib", "so", "ico", "svg", "png", "py")