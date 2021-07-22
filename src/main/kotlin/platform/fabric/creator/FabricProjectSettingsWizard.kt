/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.fabric.creator

import com.demonwav.mcdev.asset.PlatformAssets
import com.demonwav.mcdev.creator.MinecraftModuleWizardStep
import com.demonwav.mcdev.creator.MinecraftProjectCreator
import com.demonwav.mcdev.creator.ValidatedField
import com.demonwav.mcdev.creator.ValidatedFieldType.LIST
import com.demonwav.mcdev.creator.ValidatedFieldType.NON_BLANK
import com.demonwav.mcdev.platform.PlatformType
import com.demonwav.mcdev.platform.fabric.EntryPoint
import com.demonwav.mcdev.platform.fabric.util.FabricConstants
import com.demonwav.mcdev.platform.forge.inspections.sideonly.Side
import com.demonwav.mcdev.util.License
import com.demonwav.mcdev.util.MinecraftVersions
import com.demonwav.mcdev.util.SemanticVersion
import com.demonwav.mcdev.util.modUpdateStep
import com.demonwav.mcdev.util.toPackageName
import com.extracraftx.minecraft.templatemakerfabric.data.DataProvider
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.EditableModel
import com.intellij.util.ui.table.ComboBoxTableCellEditor
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.util.Collections
import java.util.Locale
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextField
import javax.swing.table.AbstractTableModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.apache.commons.lang.WordUtils

class FabricProjectSettingsWizard(private val creator: MinecraftProjectCreator) : MinecraftModuleWizardStep() {

    // Initialize ALL custom fields in createUIComponents, otherwise they are null until after that point!
    @ValidatedField(NON_BLANK)
    private lateinit var modNameField: JTextField
    private lateinit var panel: JPanel
    private lateinit var title: JLabel
    private lateinit var descriptionField: JTextField

    @ValidatedField(LIST)
    private lateinit var authorsField: JTextField
    private lateinit var websiteField: JTextField
    private lateinit var repositoryField: JTextField
    private lateinit var minecraftVersionBox: JComboBox<String>
    private lateinit var loaderVersionBox: JComboBox<String>
    private lateinit var yarnVersionBox: JComboBox<String>
    private lateinit var loomVersionBox: JComboBox<String>
    private lateinit var licenseBox: JComboBox<License>
    private lateinit var useFabricApiCheckbox: JCheckBox
    private lateinit var fabricApiBox: JComboBox<String>
    private lateinit var environmentBox: JComboBox<String>
    private lateinit var mixinsCheckbox: JCheckBox
    private lateinit var decompileMcCheckbox: JCheckBox
    private lateinit var loadingBar: JProgressBar
    private lateinit var minecraftVersionLabel: JLabel
    private lateinit var entryPointsTable: JPanel
    private lateinit var entryPoints: ArrayList<EntryPoint>
    private lateinit var tableModel: EntryPointTableModel
    private lateinit var yarnWarning: JLabel
    private lateinit var errorLabel: JLabel

    private var config: FabricProjectConfig? = null

    private var dataProvider: DataProvider? = null

    private var currentJob: Job? = null

    private var initializedEntryPointsTable = false

    private val minecraftBoxActionListener: ActionListener = ActionListener {
        yarnVersionBox.selectedItem = null
        loaderVersionBox.selectedItem = null
        loomVersionBox.selectedItem = null
        fabricApiBox.selectedItem = null
        updateForm()
    }

    init {
        yarnWarning.isVisible = false
        errorLabel.isVisible = false
    }

    fun createUIComponents() {
        entryPoints = arrayListOf()

        tableModel = EntryPointTableModel(entryPoints)
        val entryPointsTable = JBTable(tableModel)
        entryPointsTable.setDefaultEditor(EntryPoint.Type::class.java, ComboBoxTableCellEditor.INSTANCE)
        fun resizeColumns() {
            val model = entryPointsTable.columnModel
            val totalWidth = model.totalColumnWidth
            model.getColumn(0).preferredWidth = (totalWidth * 0.1).toInt()
            model.getColumn(1).preferredWidth = (totalWidth * 0.1).toInt()
            model.getColumn(2).preferredWidth = (totalWidth * 0.3).toInt()
            model.getColumn(3).preferredWidth = (totalWidth * 0.3).toInt()
            model.getColumn(4).preferredWidth = (totalWidth * 0.2).toInt()
        }
        resizeColumns()
        entryPointsTable.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    resizeColumns()
                }
            }
        )
        this.entryPointsTable = ToolbarDecorator.createDecorator(entryPointsTable).createPanel()

        licenseBox = ComboBox(CollectionComboBoxModel(enumValues<License>().toList()))
        licenseBox.selectedItem = License.MIT
    }

    override fun getComponent(): JComponent {
        return panel
    }

    override fun updateStep() {
        val (conf, buildSystem) = modUpdateStep<FabricProjectConfig>(creator, modNameField) ?: return
        config = conf

        if (creator.configs.indexOf(conf) != 0) {
            modNameField.isEditable = false
        }

        if (!initializedEntryPointsTable) {
            val packageName = "${buildSystem.groupId.toPackageName()}.${buildSystem.artifactId.toPackageName()}"
            var className = buildSystem.artifactId.replace('-', ' ').let { WordUtils.capitalize(it) }.replace(" ", "")
            if (creator.configs.size > 1) {
                className += PlatformType.FABRIC.normalName
            }
            entryPoints.add(
                EntryPoint(
                    "main",
                    EntryPoint.Type.CLASS,
                    "$packageName.$className",
                    FabricConstants.MOD_INITIALIZER
                )
            )
            entryPoints.add(
                EntryPoint(
                    "client",
                    EntryPoint.Type.CLASS,
                    "$packageName.client.${className}Client",
                    FabricConstants.CLIENT_MOD_INITIALIZER
                )
            )
            tableModel.fireTableDataChanged()
            entryPointsTable.revalidate()
            initializedEntryPointsTable = true
        }

        title.icon = PlatformAssets.FABRIC_ICON_2X
        title.text = "<html><font size=\"5\">Fabric Settings</font></html>"

        minecraftVersionLabel.text = "Minecraft Version"

        if (dataProvider != null || currentJob?.isActive == true) {
            return
        }
        currentJob = updateVersions()
    }

    private val mcVersion: String?
        get() = minecraftVersionBox.selectedItem as? String

    private val yarnVersion: String?
        get() = yarnVersionBox.selectedItem as? String

    private val loomVersion: String?
        get() = loomVersionBox.selectedItem as? String

    private val loaderVersion: String?
        get() = loaderVersionBox.selectedItem as? String

    private val fabricApiVersion: String?
        get() = fabricApiBox.selectedItem as? String

    override fun validate(): Boolean {
        return super.validate() && !loadingBar.isVisible
    }

    override fun isStepVisible(): Boolean {
        return creator.configs.any { it is FabricProjectConfig }
    }

    override fun onStepLeaving() {
        currentJob?.cancel()
    }

    fun error() {
        errorLabel.isVisible = true
        loadingBar.isIndeterminate = false
        loadingBar.isVisible = false
    }

    override fun updateDataModel() {
        val conf = config ?: return
        conf.pluginName = modNameField.text
        conf.description = descriptionField.text
        conf.website = websiteField.text

        conf.setAuthors(authorsField.text)
        conf.modRepo = repositoryField.text

        conf.yarnVersion = yarnVersion ?: "$mcVersion+build.1"
        val yarnVersionObj = dataProvider?.yarnVersions?.firstOrNull { it.name == yarnVersion }
        conf.yarnClassifier = if (yarnVersionObj?.hasV2Mappings == false) {
            null
        } else {
            "v2"
        }
        conf.mcVersion = mcVersion ?: ""
        val normalizedMcVersion = dataProvider?.getNormalizedMinecraftVersion(mcVersion)?.normalized
        conf.semanticMcVersion = normalizedMcVersion?.let { SemanticVersion.parse(it) } ?: SemanticVersion.release()
        conf.javaVersion = if (conf.semanticMcVersion >= MinecraftVersions.MC1_17) 16 else 8
        val loaderVer = loaderVersion
        if (loaderVer != null) {
            conf.loaderVersion = SemanticVersion.parse(loaderVer)
        }
        val api = if (useFabricApiCheckbox.isSelected) {
            dataProvider?.fabricApiVersions?.firstOrNull { it.name == fabricApiVersion }
        } else {
            null
        }
        conf.apiVersion = api?.mavenVersion?.let { SemanticVersion.parse(it) }
        conf.apiMavenLocation = api?.mavenLocation
        conf.gradleVersion = when (dataProvider?.loomVersions?.firstOrNull { it.name == loomVersion }?.gradle) {
            4 -> SemanticVersion.release(4, 10, 3)
            5 -> SemanticVersion.release(5, 6, 4)
            else -> SemanticVersion.release(6, 9)
        }
        val loomVer = loomVersion
        if (loomVer != null) {
            conf.loomVersion = SemanticVersion.parse(loomVer)
        }
        if (conf.loomVersion >= SemanticVersion.release(0, 7)) {
            // TemplateMakerFabric incorrectly indicates loom 0.8 requires Gradle 6...
            conf.gradleVersion = SemanticVersion.release(7, 1, 1)
        }
        conf.environment = when ((environmentBox.selectedItem as? String)?.toLowerCase(Locale.ROOT)) {
            "client" -> Side.CLIENT
            "server" -> Side.SERVER
            else -> Side.NONE
        }
        conf.license = licenseBox.selectedItem as? License
        conf.entryPoints = entryPoints.filter { it.valid }
        conf.mixins = mixinsCheckbox.isSelected
        conf.genSources = decompileMcCheckbox.isSelected
    }

    private fun updateVersions() = CoroutineScope(Dispatchers.Swing).launch {
        loadingBar.isIndeterminate = true
        loadingBar.isVisible = true

        try {
            dataProvider = downloadVersions()
            updateForm()
        } catch (e: Exception) {
            e.printStackTrace()
            error()
        }

        loadingBar.isIndeterminate = false
        loadingBar.isVisible = false

        currentJob = null
    }

    private suspend fun downloadVersions(): DataProvider? = coroutineScope {
        // prefetch the data
        val dataProvider = DataProvider()
        val minecraftVersionJob = async(Dispatchers.IO) { runCatching { dataProvider.minecraftVersions }.getOrNull() }
        val fabricApiVersionJob = async(Dispatchers.IO) { runCatching { dataProvider.fabricApiVersions }.getOrNull() }
        val yarnVersionJob = async(Dispatchers.IO) { runCatching { dataProvider.yarnVersions }.getOrNull() }
        val loomVersionJob = async(Dispatchers.IO) { runCatching { dataProvider.loomVersions }.getOrNull() }
        val loaderVersionJob = async(Dispatchers.IO) { runCatching { dataProvider.loaderVersions }.getOrNull() }

        minecraftVersionJob.await() ?: return@coroutineScope null
        fabricApiVersionJob.await() ?: return@coroutineScope null
        yarnVersionJob.await() ?: return@coroutineScope null
        loomVersionJob.await() ?: return@coroutineScope null
        loaderVersionJob.await() ?: return@coroutineScope null

        return@coroutineScope dataProvider
    }

    private fun updateForm() {
        val dp = dataProvider ?: return

        val mcVer = mcVersion ?: dp.minecraftVersions.firstOrNull { it.stable }?.name
        val mcVerObj = dp.minecraftVersions.firstOrNull { it.name == mcVer }

        val yarnVer = yarnVersion ?: mcVerObj?.let { mvo ->
            dp.getFilteredYarnVersions(mvo).firstOrNull()?.name
        }
        val yarnVerObj = dp.yarnVersions.firstOrNull { it.name == yarnVer }

        val loomVer = loomVersion ?: yarnVerObj?.let { dp.getDefaultLoomVersion(it) }?.name
        val loomVerObj = dp.loomVersions.firstOrNull { it.name == loomVer }

        val loaderVer = loaderVersion ?: loomVerObj?.let { lvo ->
            dp.getFilteredLoaderVersions(lvo).firstOrNull()?.name
        }

        val fabricVer = fabricApiVersion ?: mcVerObj?.let { mvo ->
            dp.getDefaultFabricApiVersion(mvo)
        }?.let { dp.sortedFabricApiVersions[it] }?.name

        minecraftVersionBox.removeActionListener(minecraftBoxActionListener)
        minecraftVersionBox.model = CollectionComboBoxModel(dp.minecraftVersions.map { it.name })
        minecraftVersionBox.selectedItem = mcVer
        minecraftVersionBox.addActionListener(minecraftBoxActionListener)
        yarnVersionBox.model = CollectionComboBoxModel(dp.yarnVersions.map { it.name })
        yarnVersionBox.selectedItem = yarnVer
        loomVersionBox.model = CollectionComboBoxModel(dp.loomVersions.map { it.name })
        loomVersionBox.selectedItem = loomVer
        loaderVersionBox.model = CollectionComboBoxModel(dp.loaderVersions.map { it.name })
        loaderVersionBox.selectedItem = loaderVer
        fabricApiBox.model = CollectionComboBoxModel(dp.fabricApiVersions.map { it.name })
        fabricApiBox.selectedItem = fabricVer
        useFabricApiCheckbox.isSelected = fabricVer != null
    }

    class EntryPointTableModel(private val entryPoints: ArrayList<EntryPoint>) : AbstractTableModel(), EditableModel {

        override fun getColumnName(col: Int) = when (col) {
            0 -> "Category"
            1 -> "Type"
            2 -> "Class"
            3 -> "Interface"
            else -> "Method Name"
        }

        override fun getRowCount() = entryPoints.size

        override fun getColumnCount() = 5

        override fun getValueAt(row: Int, col: Int): Any? = when (col) {
            0 -> entryPoints[row].category
            1 -> entryPoints[row].type
            2 -> entryPoints[row].className
            3 -> entryPoints[row].interfaceName
            else -> entryPoints[row].methodName
        }

        override fun isCellEditable(row: Int, col: Int): Boolean {
            return col != 4 || entryPoints.getOrNull(row)?.type == EntryPoint.Type.METHOD
        }

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            when (col) {
                0 -> entryPoints[row] = entryPoints[row].copy(category = value.toString())
                1 -> entryPoints[row] = entryPoints[row].copy(type = parseEntryPointType(value))
                2 -> entryPoints[row] = entryPoints[row].copy(className = value.toString())
                3 -> entryPoints[row] = entryPoints[row].copy(interfaceName = value.toString())
                4 -> entryPoints[row] = entryPoints[row].copy(methodName = value.toString())
            }
            fireTableCellUpdated(row, col)
        }

        private fun parseEntryPointType(value: Any?): EntryPoint.Type {
            return when (value) {
                is EntryPoint.Type -> value
                else -> enumValues<EntryPoint.Type>().firstOrNull {
                    it.name.equals(value.toString(), ignoreCase = true)
                } ?: EntryPoint.Type.CLASS
            }
        }

        override fun removeRow(idx: Int) {
            entryPoints.removeAt(idx)
        }

        override fun exchangeRows(oldIndex: Int, newIndex: Int) {
            Collections.swap(entryPoints, oldIndex, newIndex)
        }

        override fun canExchangeRows(oldIndex: Int, newIndex: Int) = true

        override fun addRow() {
            entryPoints.add(EntryPoint("", EntryPoint.Type.CLASS, "", "", ""))
        }

        override fun getColumnClass(col: Int): Class<*> {
            return if (col == 1) {
                EntryPoint.Type::class.java
            } else {
                String::class.java
            }
        }
    }
}
