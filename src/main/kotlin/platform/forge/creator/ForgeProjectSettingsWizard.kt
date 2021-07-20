/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2021 minecraft-dev
 *
 * MIT License
 */

package com.demonwav.mcdev.platform.forge.creator

import com.demonwav.mcdev.asset.PlatformAssets
import com.demonwav.mcdev.creator.MinecraftModuleWizardStep
import com.demonwav.mcdev.creator.MinecraftProjectCreator
import com.demonwav.mcdev.creator.ValidatedField
import com.demonwav.mcdev.creator.ValidatedFieldType.CLASS_NAME
import com.demonwav.mcdev.creator.ValidatedFieldType.LIST
import com.demonwav.mcdev.creator.ValidatedFieldType.NON_BLANK
import com.demonwav.mcdev.platform.PlatformType
import com.demonwav.mcdev.platform.forge.version.ForgeVersion
import com.demonwav.mcdev.platform.mcp.version.McpVersion
import com.demonwav.mcdev.platform.mcp.version.McpVersionEntry
import com.demonwav.mcdev.util.License
import com.demonwav.mcdev.util.SemanticVersion
import com.demonwav.mcdev.util.modUpdateStep
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.EnumComboBoxModel
import java.awt.event.ActionListener
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextField
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

class ForgeProjectSettingsWizard(private val creator: MinecraftProjectCreator) : MinecraftModuleWizardStep() {

    @ValidatedField(NON_BLANK)
    private lateinit var modNameField: JTextField

    @ValidatedField(NON_BLANK, CLASS_NAME)
    private lateinit var mainClassField: JTextField
    private lateinit var panel: JPanel
    private lateinit var title: JLabel
    private lateinit var descriptionField: JTextField

    @ValidatedField(LIST)
    private lateinit var authorsField: JTextField
    private lateinit var websiteField: JTextField
    private lateinit var updateUrlField: JTextField
    private lateinit var licenseBox: JComboBox<License>
    private lateinit var mixinsCheckbox: JCheckBox
    private lateinit var minecraftVersionBox: JComboBox<SemanticVersion>
    private lateinit var forgeVersionBox: JComboBox<SemanticVersion>
    private lateinit var mcpVersionBox: JComboBox<McpVersionEntry>
    private lateinit var loadingBar: JProgressBar
    private lateinit var minecraftVersionLabel: JLabel
    private lateinit var mcpWarning: JLabel
    private lateinit var errorLabel: JLabel

    private var config: ForgeProjectConfig? = null

    private data class ForgeVersions(
        var mcpVersion: McpVersion,
        var forgeVersion: ForgeVersion
    )

    private var versions: ForgeVersions? = null

    private var currentJob: Job? = null

    private val mcpBoxActionListener = ActionListener {
        mcpWarning.isVisible = (mcpVersionBox.selectedItem as? McpVersionEntry)?.isRed == true
    }

    private val forgeVersionBoxListener = ActionListener {
        val selectedVersion = forgeVersionBox.selectedItem as? SemanticVersion ?: return@ActionListener
        val supportedMixinVersion = selectedVersion >= SemanticVersion.release(31, 2, 45)
        mixinsCheckbox.isEnabled = supportedMixinVersion
        if (!supportedMixinVersion) {
            mixinsCheckbox.isSelected = false
        }
    }

    private val minecraftBoxActionListener: ActionListener = ActionListener {
        CoroutineScope(Dispatchers.Swing).launch {
            loadingBar.isIndeterminate = true
            loadingBar.isVisible = true

            updateForm()

            loadingBar.isIndeterminate = false
            loadingBar.isVisible = false
        }
    }

    init {
        mcpWarning.isVisible = false
    }

    override fun getComponent(): JComponent {
        return panel
    }

    override fun updateStep() {
        val (conf, buildSystem) = modUpdateStep<ForgeProjectConfig>(creator, modNameField) ?: return
        config = conf

        if (creator.configs.indexOf(conf) != 0) {
            modNameField.isEditable = false
        }

        mainClassField.text = generateClassName(buildSystem, modNameField.text)

        if (creator.configs.size > 1) {
            mainClassField.text = mainClassField.text + PlatformType.FORGE.normalName
        }

        title.icon = PlatformAssets.FORGE_ICON_2X
        title.text = "<html><font size=\"5\">Forge Settings</font></html>"

        minecraftVersionLabel.text = "Minecraft Version"

        if (versions != null || currentJob?.isActive == true) {
            return
        }
        currentJob = updateVersions()

        licenseBox.model = EnumComboBoxModel(License::class.java)
        licenseBox.selectedItem = License.ALL_RIGHTS_RESERVED
    }

    private fun setForgeVersion(data: Data) {
        forgeVersionBox.model = CollectionComboBoxModel(data.forgeVersions.subList(0, min(50, data.forgeVersions.size)))
        forgeVersionBox.selectedIndex = data.forgeSelectedIndex
    }

    private val version: SemanticVersion?
        get() = minecraftVersionBox.selectedItem as? SemanticVersion

    override fun validate(): Boolean {
        return super.validate() && !loadingBar.isVisible
    }

    override fun isStepVisible(): Boolean {
        return creator.configs.any { it is ForgeProjectConfig }
    }

    override fun onStepLeaving() {
        currentJob?.cancel()
    }

    override fun updateDataModel() {
        val conf = this.config ?: return

        conf.pluginName = this.modNameField.text
        conf.mainClass = this.mainClassField.text
        conf.description = this.descriptionField.text
        conf.website = this.websiteField.text

        conf.setAuthors(this.authorsField.text)
        conf.updateUrl = this.updateUrlField.text

        conf.mcpVersion = (this.mcpVersionBox.selectedItem as McpVersionEntry).versionPair

        (this.forgeVersionBox.selectedItem as SemanticVersion).let { version ->
            val versionString = version.toString()
            val forgeVersion = this.versions?.forgeVersion ?: return@let
            conf.forgeVersionText = forgeVersion.versions.first { it.endsWith(versionString) }
            conf.forgeVersion = version
        }

        conf.mixins = mixinsCheckbox.isSelected
        conf.license = licenseBox.selectedItem as? License ?: License.ALL_RIGHTS_RESERVED
        conf.mcVersion = this.version ?: SemanticVersion.release()
    }

    private fun mcVersionUpdate(data: Data) {
        mcpVersionBox.removeActionListener(mcpBoxActionListener)
        mcpVersionBox.model = CollectionComboBoxModel(data.mcpVersions.subList(0, min(50, data.mcpVersions.size)))
        mcpVersionBox.selectedIndex = 0
        mcpVersionBox.addActionListener(mcpBoxActionListener)
        mcpBoxActionListener.actionPerformed(null)

        forgeVersionBox.removeActionListener(forgeVersionBoxListener)
        setForgeVersion(data)
        forgeVersionBox.addActionListener(forgeVersionBoxListener)
        forgeVersionBoxListener.actionPerformed(null)
    }

    private fun updateVersions() = CoroutineScope(Dispatchers.Swing).launch {
        loadingBar.isIndeterminate = true
        loadingBar.isVisible = true

        try {
            downloadVersions()
            val data = updateForm()
            if (data != null) {
                updateMcForm(data)
            }
        } catch (e: Exception) {
            error()
        }

        loadingBar.isIndeterminate = false
        loadingBar.isVisible = false

        currentJob = null
    }

    fun error() {
        errorLabel.isVisible = true
        loadingBar.isIndeterminate = false
        loadingBar.isVisible = false
    }

    private suspend fun downloadVersions() = coroutineScope {
        val mcpVersionJob = async(Dispatchers.IO) { McpVersion.downloadData() }
        val forgeVersionJob = async(Dispatchers.IO) { ForgeVersion.downloadData() }

        versions = ForgeVersions(
            mcpVersionJob.await() ?: return@coroutineScope,
            forgeVersionJob.await() ?: return@coroutineScope
        )
    }

    private suspend fun updateForm(): Data? = coroutineScope {
        val vers = versions ?: return@coroutineScope null

        val selectedVersion = version ?: vers.forgeVersion.sortedMcVersions.firstOrNull() ?: return@coroutineScope null

        val mcpVersionListJob = async(Dispatchers.IO) { vers.mcpVersion.getMcpVersionList(selectedVersion) }
        val forgeVersionsJob = async(Dispatchers.IO) { vers.forgeVersion.getForgeVersions(selectedVersion) }

        val mcpVersionList = mcpVersionListJob.await()
        val forgeVersions = forgeVersionsJob.await()

        val data = Data(0, mcpVersionList, forgeVersions, 0)

        mcVersionUpdate(data)

        return@coroutineScope data
    }

    private fun updateMcForm(data: Data) {
        val vers = versions ?: return

        minecraftVersionBox.removeActionListener(minecraftBoxActionListener)
        minecraftVersionBox.removeAllItems()

        minecraftVersionBox.model = CollectionComboBoxModel(vers.forgeVersion.sortedMcVersions)
        minecraftVersionBox.selectedIndex = data.mcSelectedIndex
        minecraftVersionBox.addActionListener(minecraftBoxActionListener)
    }

    private data class Data(
        val mcSelectedIndex: Int,
        val mcpVersions: List<McpVersionEntry>,
        val forgeVersions: List<SemanticVersion>,
        val forgeSelectedIndex: Int
    )
}
