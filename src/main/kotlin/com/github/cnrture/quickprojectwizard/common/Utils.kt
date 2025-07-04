package com.github.cnrture.quickprojectwizard.common

import com.github.cnrture.quickprojectwizard.common.file.FileWriter
import com.github.cnrture.quickprojectwizard.common.file.ImportAnalyzer
import com.github.cnrture.quickprojectwizard.common.file.LibraryDependencyFinder
import com.github.cnrture.quickprojectwizard.data.FeatureTemplate
import com.github.cnrture.quickprojectwizard.data.ModuleTemplate
import com.github.cnrture.quickprojectwizard.data.PluginListItem
import com.github.cnrture.quickprojectwizard.service.AnalyticsService
import com.intellij.ide.BrowserUtil
import com.intellij.ide.starters.local.GeneratorTemplateFile
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import freemarker.template.Configuration
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.StringWriter
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

object Utils {
    val analyticsService = AnalyticsService.getInstance()
    fun validateFeatureInput(featureName: String, selectedSrc: String): Boolean =
        featureName.isNotEmpty() && selectedSrc != Constants.DEFAULT_SRC_VALUE

    fun createFeature(
        project: Project,
        selectedSrc: String,
        featureName: String,
        fileWriter: FileWriter,
        selectedTemplate: FeatureTemplate,
        from: String,
    ) {
        try {
            val projectRoot = project.rootDirectoryString()

            val cleanSelectedPath = selectedSrc.let { path ->
                val projectName = projectRoot.split(File.separator).last()
                if (path.startsWith(projectName + File.separator)) {
                    path.substring(projectName.length + 1)
                } else {
                    path
                }
            }

            val packagePath = cleanSelectedPath
                .replace(Regex("^.*?(/src/main/java/|/src/main/kotlin/)"), Constants.EMPTY)
                .replace("/", ".")

            fileWriter.createFeatureFiles(
                file = File(projectRoot, cleanSelectedPath),
                featureName = featureName,
                packageName = packagePath.plus(".${featureName.lowercase()}"),
                showErrorDialog = {
                    showInfo(
                        message = "Error creating feature: $it",
                        type = NotificationType.ERROR
                    )
                },
                showSuccessDialog = {
                    analyticsService.track("${from}_feature_created")
                    showInfo(
                        message = "Feature '$featureName' created successfully",
                        type = NotificationType.INFORMATION
                    )
                    val currentlySelectedFile = project.getCurrentlySelectedFile(selectedSrc)
                    listOf(currentlySelectedFile).refreshFileSystem()
                },
                selectedTemplate = selectedTemplate
            )
        } catch (e: Exception) {
            showInfo(
                message = "Error creating feature: ${e.message}",
                type = NotificationType.ERROR
            )
        }
    }

    fun validateModuleInput(packageName: String, moduleName: String): Boolean =
        packageName.isNotEmpty() && moduleName.isNotEmpty() && moduleName != Constants.DEFAULT_MODULE_NAME

    fun createModule(
        project: Project,
        fileWriter: FileWriter,
        selectedSrc: String,
        packageName: String,
        moduleName: String,
        name: String = Constants.EMPTY,
        moduleType: String,
        isMoveFiles: Boolean,
        libraryDependencyFinder: LibraryDependencyFinder,
        selectedLibraries: List<String>,
        selectedModules: List<String>,
        selectedPlugins: List<PluginListItem> = emptyList(),
        template: ModuleTemplate? = null,
        from: String,
    ): List<File> {
        try {
            val settingsGradleFile = getSettingsGradleFile(project)
            val selectedSrcPath = selectedSrc
            val sourceFile = getSourceDirectoryFromSelected(project, selectedSrcPath)

            if (settingsGradleFile != null) {
                val moduleName = moduleName.trim()
                if (!moduleName.startsWith(":")) {
                    showInfo(
                        message = "Module name must start with ':' (e.g. ':home' or ':feature:home')",
                        type = NotificationType.ERROR
                    )
                    return emptyList()
                }

                val moduleNameTrimmed = moduleName.removePrefix(":").replace(":", ".")
                val finalPackageName =
                    "${packageName}.${moduleNameTrimmed.split(".").joinToString(".") { it.lowercase() }}"

                val manualLibraryDependenciesString =
                    libraryDependencyFinder.formatLibraryDependencies(selectedLibraries)

                val combinedLibraryDependencies = listOf(manualLibraryDependenciesString)
                    .filter { it.isNotEmpty() }
                    .joinToString("\n")

                val pluginDependenciesString = libraryDependencyFinder.formatPluginDependencies(selectedPlugins)

                val filesCreated = fileWriter.createModule(
                    packageName = finalPackageName,
                    settingsGradleFile = settingsGradleFile,
                    modulePathAsString = moduleName,
                    name = name,
                    moduleType = moduleType,
                    showErrorDialog = {
                        showInfo(
                            message = "Error creating module: $it",
                            type = NotificationType.ERROR
                        )
                    },
                    showSuccessDialog = {
                        analyticsService.track("${from}_module_created")
                        showInfo(
                            message = "Module '$moduleName' created successfully",
                            type = NotificationType.INFORMATION
                        )

                        val projectDir = File(project.basePath.orEmpty())
                        VfsUtil.markDirtyAndRefresh(false, true, true, VfsUtil.findFileByIoFile(projectDir, true))

                        if (isMoveFiles) {
                            moveFilesToNewModule(
                                project = project,
                                sourceDir = sourceFile,
                                targetModulePath = moduleName,
                                packageName = finalPackageName,
                                isMoveFiles = true,
                            )
                        } else {
                            val modulePath = File(project.basePath, moduleName.replace(":", "/"))
                            ApplicationManager.getApplication().invokeLater {
                                openNewModule(project, modulePath, emptyList())
                            }
                        }

                        addDependencyToAppModule(project, moduleName)
                        syncProject(project)

                        ApplicationManager.getApplication().invokeLater {
                            ToolWindowManager.getInstance(project).getToolWindow("QuickProjectWizard")?.hide()
                        }
                    },
                    workingDirectory = File(project.basePath.orEmpty()),
                    dependencies = selectedModules,
                    libraryDependencies = combinedLibraryDependencies,
                    pluginDependencies = pluginDependenciesString,
                    template = template,
                )
                return filesCreated
            } else {
                showInfo(
                    message = "Couldn't find settings.gradle(.kts) file",
                    type = NotificationType.ERROR
                )
                return emptyList()
            }
        } catch (e: Exception) {
            showInfo(
                message = "Error creating module: ${e.message}",
                type = NotificationType.ERROR
            )
            return emptyList()
        }
    }

    fun moveFilesToNewModule(
        project: Project,
        sourceDir: File,
        targetModulePath: String,
        packageName: String,
        isMoveFiles: Boolean,
    ) {
        if (!isMoveFiles) return

        try {
            if (!sourceDir.exists() || !sourceDir.isDirectory) {
                showInfo(
                    message = "Source directory does not exist or is not a directory",
                    type = NotificationType.ERROR
                )
                return
            }

            val modulePath = File(project.basePath, targetModulePath.replace(":", "/"))
            val targetSrcDir = File(modulePath, "src/main/kotlin")
            targetSrcDir.mkdirs()

            val packagePath = packageName.split(".").joinToString(File.separator)
            val targetPackageDir = File(targetSrcDir, packagePath)
            targetPackageDir.mkdirs()

            val sourceFiles = sourceDir.walkTopDown().filter {
                it.isFile && (it.extension == "kt" || it.extension == "java")
            }.toList()

            if (sourceFiles.isEmpty()) {
                showInfo(
                    message = "No source files found to move in ${sourceDir.absolutePath}",
                    type = NotificationType.WARNING
                )
                return
            }

            val movedFiles = mutableListOf<VirtualFile>()
            val packageMappings = mutableMapOf<String, String>()
            val filePathMappings = mutableMapOf<String, File>()

            sourceFiles.forEach { sourceFile ->
                try {
                    val relativePath = getRelativePath(sourceFile, sourceDir)
                    val targetFile = File(targetPackageDir, relativePath)
                    targetFile.parentFile.mkdirs()
                    sourceFile.copyTo(targetFile, overwrite = true)

                    val relativeDir = targetFile.parentFile.absolutePath
                        .removePrefix(targetPackageDir.absolutePath)
                        .trim(File.separatorChar)

                    val subPackage = if (relativeDir.isNotEmpty()) {
                        "." + relativeDir.replace(File.separator, ".")
                    } else {
                        Constants.EMPTY
                    }

                    val fullPackageName = packageName + subPackage
                    val content = targetFile.readText()
                    val packagePattern = """package\s+([a-zA-Z0-9_.]+)""".toRegex()
                    val packageMatch = packagePattern.find(content)
                    val originalPackage = packageMatch?.groupValues?.get(1).orEmpty()

                    if (originalPackage.isNotEmpty()) packageMappings[originalPackage] = fullPackageName

                    val updatedContent = packagePattern.replace(content, "package $fullPackageName")

                    if (content != updatedContent) targetFile.writeText(updatedContent)

                    filePathMappings[sourceFile.absolutePath] = targetFile

                    VfsUtil.findFileByIoFile(targetFile, true)?.let { movedFiles.add(it) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            filePathMappings.values.forEach { targetFile ->
                try {
                    val content = targetFile.readText()
                    var updatedContent = content

                    packageMappings.forEach { (oldPackage, newPackage) ->
                        val importPattern = """import\s+$oldPackage\.([a-zA-Z0-9_.]+)""".toRegex()
                        updatedContent = updatedContent.replace(importPattern) { matchResult ->
                            val subpath = matchResult.groupValues[1]
                            "import $newPackage.$subpath"
                        }
                    }

                    if (content != updatedContent) targetFile.writeText(updatedContent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val projectDir = File(project.basePath.orEmpty())
            VfsUtil.markDirtyAndRefresh(false, true, true, VfsUtil.findFileByIoFile(projectDir, true))

            ApplicationManager.getApplication().invokeLater {
                openNewModule(project, modulePath, movedFiles)
            }
            showInfo(
                message = "Files moved to new module: ${targetModulePath.replace(":", "/")}",
                type = NotificationType.INFORMATION
            )
        } catch (e: Exception) {
            showInfo(
                message = "Error moving files: ${e.message}",
                type = NotificationType.ERROR
            )
            e.printStackTrace()
        }
    }

    fun openNewModule(project: Project, modulePath: File, filesToOpen: List<VirtualFile>) {
        try {
            val moduleRootDir = VfsUtil.findFileByIoFile(modulePath, true)
            if (moduleRootDir != null) {
                val buildGradleFile = moduleRootDir.findChild("build.gradle.kts")
                    ?: moduleRootDir.findChild("build.gradle")

                if (buildGradleFile != null) {
                    FileEditorManager.getInstance(project).openFile(buildGradleFile, true)
                }

                filesToOpen.take(5).forEach { file ->
                    FileEditorManager.getInstance(project).openFile(file, true)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSettingsGradleFile(project: Project): File? {
        val settingsGradleKtsPath = File(project.basePath, "settings.gradle.kts")
        val settingsGradlePath = File(project.basePath, "settings.gradle")

        val settingsFile = listOf(settingsGradleKtsPath, settingsGradlePath).firstOrNull {
            it.exists()
        } ?: run {
            showInfo(
                message = "Can't find settings.gradle(.kts) file in project: ${project.name}",
                type = NotificationType.ERROR
            )
            return null
        }

        try {
            val content = settingsFile.readText()
            if (!content.contains("TYPESAFE_PROJECT_ACCESSORS")) {
                val lines = content.lines().toMutableList()
                var insertIndex = -1

                for (i in lines.indices) {
                    if (lines[i].trim().startsWith("rootProject.name")) {
                        // Clean up rootProject.name by removing spaces from the value
                        val line = lines[i]
                        val namePattern = """rootProject\.name\s*=\s*["']([^"']+)["']""".toRegex()
                        val match = namePattern.find(line)
                        if (match != null) {
                            val originalName = match.groupValues[1]
                            val cleanedName = originalName.replace(" ", "")
                            if (originalName != cleanedName) {
                                val quote = if (line.contains("\"")) "\"" else "'"
                                lines[i] = line.replace(namePattern, "rootProject.name = $quote$cleanedName$quote")
                            }
                        }
                        insertIndex = i + 1
                        break
                    }
                }

                if (insertIndex != -1) {
                    lines.add(insertIndex, "enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\")")
                    settingsFile.writeText(lines.joinToString("\n"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return settingsFile
    }

    fun syncProject(project: Project) {
        val projectDir = File(project.basePath.orEmpty())
        VfsUtil.markDirtyAndRefresh(false, true, true, VfsUtil.findFileByIoFile(projectDir, true))
        ExternalSystemUtil.refreshProject(
            project,
            ProjectSystemId("GRADLE"),
            project.rootDirectoryString(),
            false,
            ProgressExecutionMode.IN_BACKGROUND_ASYNC
        )
    }

    fun getRelativePath(sourceFile: File, sourceDir: File): String {
        val sourceFilePath = sourceFile.absolutePath
        val sourceDirPath = sourceDir.absolutePath
        if (sourceFilePath.startsWith(sourceDirPath)) {
            val relPath = sourceFilePath.substring(sourceDirPath.length)
            return if (relPath.startsWith(File.separator)) relPath.substring(1) else relPath
        }
        return sourceFile.name
    }

    fun getSourceDirectoryFromSelected(project: Project, selectedPath: String): File {
        if (selectedPath.isBlank()) return File(project.basePath.orEmpty())
        val projectBasePath = project.basePath.orEmpty()
        val pathOptions = mutableListOf<File>()
        pathOptions.add(File(projectBasePath, selectedPath))
        pathOptions.add(File(selectedPath))

        val pathParts = selectedPath.split(File.separator)
        if (pathParts.size > 1) {
            val reducedPath = pathParts.drop(1).joinToString(File.separator)
            pathOptions.add(File(projectBasePath, reducedPath))
        }

        for (option in pathOptions) {
            if (option.exists() && option.isDirectory) return option
        }
        return pathOptions.first()
    }

    fun addDependencyToAppModule(project: Project, modulePathAsString: String) {
        try {
            val appGradleFile = findAppGradleFile(project)
            if (appGradleFile == null || !appGradleFile.exists()) return

            val content = appGradleFile.readText()
            val dependenciesPattern = """dependencies\s*\{([^}]*)}""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val dependenciesMatch = dependenciesPattern.find(content)

            if (dependenciesMatch != null) {
                val dependenciesBlock = dependenciesMatch.groupValues[1]
                val moduleName = modulePathAsString.removePrefix(":").replace(":", ".")
                val dependencyLine = "    implementation(projects.$moduleName)"
                if (dependenciesBlock.contains(dependencyLine)) return
                val newDependenciesBlock = "$dependenciesBlock\n$dependencyLine\n"
                val newContent =
                    content.replace(dependenciesMatch.groupValues[0], "dependencies {$newDependenciesBlock}")

                appGradleFile.writeText(newContent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun findAppGradleFile(project: Project): File? {
        val projectBasePath = project.basePath ?: return null

        val possibleAppLocations = listOf(
            "app/build.gradle",
            "app/build.gradle.kts",
            "mobile/build.gradle",
            "mobile/build.gradle.kts",
            "androidApp/build.gradle",
            "androidApp/build.gradle.kts"
        )

        for (location in possibleAppLocations) {
            val file = File(projectBasePath, location)
            if (file.exists()) return file
        }

        val appDir = File(projectBasePath).listFiles()?.firstOrNull {
            it.isDirectory && (it.name == "app" || it.name == "mobile" || it.name == "androidApp")
        }

        if (appDir != null) {
            val gradleFile = File(appDir, "build.gradle")
            val ktsFile = File(appDir, "build.gradle.kts")
            if (gradleFile.exists()) return gradleFile
            if (ktsFile.exists()) return ktsFile
        }
        return null
    }

    fun loadExistingModules(project: Project, onExistingModulesLoaded: (List<String>) -> Unit) {
        val settingsFile = getSettingsGradleFile(project)
        if (settingsFile != null) {
            try {
                val content = settingsFile.readText()
                val patterns = listOf(
                    """include\s*\(\s*["']([^"']+)["']\s*\)""".toRegex(),
                    """include\s+['"]([^"']+)[""]""".toRegex(),
                    """include\s+['"]([^"']+)[""](?:\s*,\s*['"]([^"']+)[""])*""".toRegex(),
                    """include\s+['"]([^"']+)[""](?:\s*,\s*\n\s*['"]([^"']+)[""])*""".toRegex()
                )

                val modulesSet = mutableSetOf<String>()
                patterns.forEach { pattern ->
                    val matches = pattern.findAll(content)
                    matches.forEach { matchResult ->
                        matchResult.groupValues.drop(1).forEach { moduleValue ->
                            if (moduleValue.isNotEmpty()) {
                                modulesSet.add(moduleValue)
                            }
                        }
                    }
                }

                val multiLinePattern =
                    """include\s*(?:'[^']*'|"[^"]*")\s*(?:,\s*\n\s*(?:'[^']*'|"[^"]*")\s*)*""".toRegex()
                val multiLineMatches = multiLinePattern.findAll(content)

                multiLineMatches.forEach { match ->
                    val modulePattern = """['"]([^""]+)[""]""".toRegex()
                    val moduleMatches = modulePattern.findAll(match.value)
                    moduleMatches.forEach { moduleMatch ->
                        val moduleValue = moduleMatch.groupValues[1]
                        if (moduleValue.isNotEmpty()) {
                            modulesSet.add(moduleValue)
                        }
                    }
                }
                onExistingModulesLoaded(modulesSet.toList().sorted())
            } catch (e: Exception) {
                e.printStackTrace()
                onExistingModulesLoaded(emptyList())
            }
        }
    }

    fun loadAvailableLibraries(
        project: Project,
        libraryDependencyFinder: LibraryDependencyFinder,
        onAvailableLibrariesLoaded: (List<String>) -> Unit,
        onLibraryGroupsLoaded: (Map<String, List<String>>) -> Unit,
        expandedGroups: Map<String, Boolean>,
    ) {
        thread {
            val projectRoot = File(project.basePath.orEmpty())
            if (projectRoot.exists()) {
                val libraries = libraryDependencyFinder.parseLibsVersionsToml(projectRoot)
                val libraryAliases = libraries.map { it.alias }
                val grouped = groupLibraries(libraryAliases)
                SwingUtilities.invokeLater {
                    onAvailableLibrariesLoaded(libraryAliases)
                    onLibraryGroupsLoaded(grouped)
                    expandedGroups.toMutableMap().clear()
                    grouped.keys.forEach { expandedGroups.toMutableMap()[it] = false }
                }
            }
        }
    }

    fun groupLibraries(libraries: List<String>): Map<String, List<String>> {
        val grouped = mutableMapOf<String, MutableList<String>>()
        val ungrouped = mutableListOf<String>()
        libraries.forEach { library ->
            val parts = library.split("-")
            if (parts.size > 1) {
                val prefix = parts[0]
                val relatedLibs = libraries.filter { it.startsWith("$prefix-") }
                if (relatedLibs.size > 1) {
                    grouped.getOrPut(prefix) { mutableListOf() }.add(library)
                } else {
                    ungrouped.add(library)
                }
            } else {
                ungrouped.add(library)
            }
        }
        if (ungrouped.isNotEmpty()) grouped["Other"] = ungrouped.toMutableList()
        return grouped.mapValues { it.value.sorted() }
    }

    fun loadAvailablePlugins(
        project: Project,
        libraryDependencyFinder: LibraryDependencyFinder,
        onAvailablePluginsLoaded: (List<String>) -> Unit,
    ) {
        thread {
            val projectRoot = File(project.basePath.orEmpty())
            if (projectRoot.exists()) {
                val pluginAliases = libraryDependencyFinder.parsePluginsFromToml(projectRoot).map { it.alias }
                SwingUtilities.invokeLater {
                    onAvailablePluginsLoaded(pluginAliases)
                }
            }
        }
    }

    fun analyzeSelectedDirectory(
        directory: File,
        project: Project,
        onAnalysisResultChange: (String?) -> Unit,
        onAnalyzingChange: (Boolean) -> Unit,
        onDetectedModulesLoaded: (List<String>) -> Unit,
        onSelectedModulesLoaded: (List<String>) -> Unit,
        detectedModules: List<String>,
    ) {
        try {
            if (!directory.exists() || !directory.isDirectory) {
                onAnalysisResultChange("Directory does not exist or is not a directory")
                return
            }
            onAnalyzingChange(true)
            onAnalysisResultChange(null)
            thread {
                try {
                    val analyzer = ImportAnalyzer()
                    val projectRoot = project.basePath?.let { File(it) }
                    if (projectRoot != null && projectRoot.exists()) {
                        analyzer.discoverProjectModules(projectRoot)
                    }
                    val findModules = analyzer.analyzeSourceDirectory(directory)

                    SwingUtilities.invokeLater {
                        onDetectedModulesLoaded(findModules)
                        onSelectedModulesLoaded(findModules)
                        if (detectedModules.isEmpty()) {
                            onAnalysisResultChange("No dependencies detected")
                        } else {
                            onAnalysisResultChange("Detected ${detectedModules.size} dependencies")
                        }
                        onAnalyzingChange(false)
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        onAnalysisResultChange("Error analyzing directory: ${e.message}")
                        onAnalyzingChange(false)
                    }
                }
            }
        } catch (e: Exception) {
            onAnalysisResultChange("Error analyzing directory: ${e.message}")
            onAnalyzingChange(false)
        }
    }

    fun createEmptyDirectory(parent: VirtualFile, path: String) {
        VfsUtil.createDirectoryIfMissing(parent, path)
    }

    fun generateFileFromTemplate(
        dataModel: Map<String, Any>,
        outputDir: VirtualFile,
        asset: GeneratorTemplateFile,
    ) {
        Configuration(Configuration.VERSION_2_3_33).apply {
            setClassLoaderForTemplateLoading(this::class.java.classLoader, "fileTemplates/code")
            val outputFilePathParts = asset.relativePath.split('/')
            val dirPath = outputFilePathParts.dropLast(1).joinToString("/")
            val targetDir = VfsUtil.createDirectoryIfMissing(outputDir, dirPath)
                ?: throw IOException("Failed to create directory: $dirPath")
            val outputFile = targetDir.createChildData(this, outputFilePathParts.last())
            StringWriter().use { writer ->
                val template = "${asset.template.name}.${asset.template.extension}"
                getTemplate("${template}.ft").process(dataModel, writer)
                VfsUtil.saveText(outputFile, writer.toString())
            }
        }
    }

    fun showInfo(title: String? = null, message: String, type: NotificationType = NotificationType.INFORMATION) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("QPW Notification Group")
            .createNotification(
                title = title ?: "Quick Project Wizard",
                content = message,
                type = type,
            )
        notification.addAction(
            object : AnAction("Contact Developer") {
                override fun actionPerformed(e: AnActionEvent) {
                    BrowserUtil.browse("https://candroid.dev/")
                }
            }
        )
        notification.addAction(
            object : AnAction("Open Plugin Page") {
                override fun actionPerformed(e: AnActionEvent) {
                    BrowserUtil.browse("https://quickprojectwizard.candroid.dev/overview")
                }
            }
        )
        notification.icon = IconLoader.getIcon("/META-INF/pluginIcon.svg", this::class.java)
        notification.notify(null)
    }

    fun exportFeatureTemplate(
        project: Project,
        template: FeatureTemplate,
        onComplete: (Boolean, String) -> Unit,
    ) {
        try {
            val descriptor = FileChooserDescriptorFactory
                .createSingleFolderDescriptor()
            descriptor.title = "Select Export Location"

            FileChooser.chooseFile(descriptor, project, null) { file ->
                try {
                    val json = Json {
                        prettyPrint = true
                        encodeDefaults = true
                    }
                    val jsonString = json.encodeToString(FeatureTemplate.serializer(), template)
                    val exportFile = File(file.path, "${template.name.replace(" ", "_")}_feature_template.json")
                    exportFile.writeText(jsonString)
                    onComplete(true, "Feature template exported successfully to: ${exportFile.absolutePath}")
                } catch (e: Exception) {
                    onComplete(false, "Error exporting feature template: ${e.message}")
                }
            }
        } catch (e: Exception) {
            onComplete(false, "Error during export: ${e.message}")
        }
    }

    fun importFeatureTemplate(
        project: Project,
        onComplete: (FeatureTemplate?, String) -> Unit,
    ) {
        try {
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json")
            descriptor.title = "Select Feature Template File"

            FileChooser.chooseFile(descriptor, project, null) { file ->
                try {
                    val jsonString = File(file.path).readText()
                    val json = Json {
                        ignoreUnknownKeys = true
                    }
                    val template = json.decodeFromString(FeatureTemplate.serializer(), jsonString)
                    onComplete(template, "Feature template imported successfully!")
                } catch (e: Exception) {
                    onComplete(null, "Error importing feature template: ${e.message}")
                }
            }
        } catch (e: Exception) {
            onComplete(null, "Error during import: ${e.message}")
        }
    }

    fun exportModuleTemplate(
        project: Project,
        template: ModuleTemplate,
        onComplete: (Boolean, String) -> Unit,
    ) {
        try {
            val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
            descriptor.title = "Select Export Location"

            FileChooser.chooseFile(descriptor, project, null) { file ->
                try {
                    val json = Json {
                        prettyPrint = true
                        encodeDefaults = true
                    }
                    val jsonString = json.encodeToString(ModuleTemplate.serializer(), template)
                    val exportFile = File(file.path, "${template.name.replace(" ", "_")}_module_template.json")
                    exportFile.writeText(jsonString)
                    onComplete(true, "Module template exported successfully to: ${exportFile.absolutePath}")
                } catch (e: Exception) {
                    onComplete(false, "Error exporting module template: ${e.message}")
                }
            }
        } catch (e: Exception) {
            onComplete(false, "Error during export: ${e.message}")
        }
    }

    fun importModuleTemplate(
        project: Project,
        onComplete: (ModuleTemplate?, String) -> Unit,
    ) {
        try {
            val descriptor = FileChooserDescriptorFactory
                .createSingleFileDescriptor("json")
            descriptor.title = "Select Module Template File"

            FileChooser.chooseFile(descriptor, project, null) { file ->
                try {
                    val jsonString = File(file.path).readText()
                    val json = Json {
                        ignoreUnknownKeys = true
                    }
                    val template = json.decodeFromString(ModuleTemplate.serializer(), jsonString)
                    onComplete(template, "Module template imported successfully!")
                } catch (e: Exception) {
                    onComplete(null, "Error importing module template: ${e.message}")
                }
            }
        } catch (e: Exception) {
            onComplete(null, "Error during import: ${e.message}")
        }
    }
}
