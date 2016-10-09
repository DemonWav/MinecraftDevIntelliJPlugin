/*
 * Minecraft Dev for IntelliJ
 *
 * https://minecraftdev.org
 *
 * Copyright (c) 2016 Kyle Wood (DemonWav)
 *
 * MIT License
 */
package com.demonwav.mcdev.platform.bukkit.yaml;

import com.demonwav.mcdev.platform.bukkit.BukkitModule;
import com.demonwav.mcdev.platform.bukkit.util.BukkitUtil;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.VirtualFileAdapter;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.yaml.psi.YAMLDocument;
import org.jetbrains.yaml.psi.YAMLFile;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLPsiElement;
import org.jetbrains.yaml.psi.YAMLScalarList;
import org.jetbrains.yaml.psi.YAMLScalarText;
import org.jetbrains.yaml.psi.impl.YAMLArrayImpl;
import org.jetbrains.yaml.psi.impl.YAMLBlockMappingImpl;
import org.jetbrains.yaml.psi.impl.YAMLFileImpl;
import org.jetbrains.yaml.psi.impl.YAMLPlainTextImpl;

import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public class PluginConfigManager {

    @NotNull private BukkitModule module;
    @NotNull private PluginConfig config;

    public PluginConfigManager(@NotNull BukkitModule module) {
        this.module = module;
        this.config = new PluginConfig(module);

        importConfig();

        VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileAdapter() {
            @Override
            public void contentsChanged(@NotNull VirtualFileEvent event) {
                if (event.getFile().equals(module.getPluginYml())) {
                    importConfig();
                    System.out.println(config.toString());
                }
            }
        });
    }

    @NotNull
    public PluginConfig getConfig() {
        return config;
    }

    private void importConfig() {
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiFile pluginYml = BukkitUtil.getPluginYml(module);
            YAMLFile file = ((YAMLFileImpl) pluginYml);

            if (file == null) {
                return; // TODO: Show warning to user
            }

            // TODO: Show warning to user if there is more than one document
            YAMLDocument document = file.getDocuments().get(0);
            YAMLBlockMappingImpl blockMapping = (YAMLBlockMappingImpl) document.getTopLevelValue();
            if (blockMapping == null) {
                return;
            }

            blockMapping.getYAMLElements().forEach(e -> {
                if (!(e instanceof YAMLKeyValue)) {
                    // TODO: Show warning to user, this would be invalid in a plugin.yml
                    return;
                }

                YAMLKeyValue keyValue = ((YAMLKeyValue) e);
                String key = keyValue.getKeyText();

                switch (key) {
                    case PluginDescriptionFileConstants.NAME:
                    case PluginDescriptionFileConstants.VERSION:
                    case PluginDescriptionFileConstants.AUTHOR:
                    case PluginDescriptionFileConstants.DESCRIPTION:
                    case PluginDescriptionFileConstants.WEBSITE:
                    case PluginDescriptionFileConstants.PREFIX:
                        handleSingleValue(key, keyValue, false);
                        break;
                    case PluginDescriptionFileConstants.MAIN:
                        handleSingleValue(PluginDescriptionFileConstants.MAIN, keyValue, false);
                        // TODO: verify this is a proper class that exists and extends JavaPlugin
                        break;
                    case PluginDescriptionFileConstants.LOAD:
                        // TODO: handle this enum & verify the value is actually valid and warn if not
                        handleSingleValue(key, keyValue, true);
                        break;
                    case PluginDescriptionFileConstants.AUTHORS:
                    case PluginDescriptionFileConstants.LOADBEFORE:
                    case PluginDescriptionFileConstants.DEPEND:
                    case PluginDescriptionFileConstants.SOFTDEPEND:
                        handleListValue(key, keyValue);
                        break;
                    case PluginDescriptionFileConstants.COMMANDS:
                        // TODO: handle commands
                        break;
                    case PluginDescriptionFileConstants.PERMISSIONS:
                        // TODO: handle permissions
                        break;
                    case PluginDescriptionFileConstants.DATABASE:
                        if (keyValue.getValueText()
                                .matches("y|Y|yes|Yes|YES|n|N|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF")) {
                            handleSingleValue(key, keyValue, false);
                        } else {
                            // TODO: show warning to user, must be a boolean value
                            setValueInConfig(key, false, false);
                        }
                        break;
                    default:
                        // TODO: Show warning to user, invalid field in plugin.yml
                }

                // Temp code for testing
                System.out.println(e.getClass().getSimpleName());
                if (e.getYAMLElements().size() != 0)
                    printYamlEles(e.getYAMLElements(), 1);
            });
            System.out.println();

        });
    }

    private void handleSingleValue(String name, YAMLPsiElement keyValue, boolean isEnum) {
        if (keyValue.getYAMLElements().size() > 1) {
            /*
             * TODO: Show warning to user. This would only be valid as 1 if The only child is a YAMLScalarList or
             * YAMLScalarText, which we will check for below.
             */
            return;
        }

        if ((keyValue.getYAMLElements().size() == 1) && (keyValue.getYAMLElements().get(0) instanceof YAMLScalarList ||
                keyValue.getYAMLElements().get(0) instanceof YAMLScalarText)) {
            // Handle scalar lists here. We will just concatenate them into a string using new lines.
            String lines[] = keyValue.getYAMLElements().get(0).getText().split("\\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < lines.length; i++) {
                sb.append(lines[i].trim());
                if (i + 1 == lines.length) {
                    if (keyValue.getYAMLElements().get(0) instanceof YAMLScalarList) {
                        sb.append("\n");
                    } else {
                        sb.append(" ");
                    }
                }
            }
            setValueInConfig(name, sb.toString(), isEnum);
            return;
        }

        if (keyValue.getYAMLElements().size() == 1) {
            if (keyValue.getYAMLElements().get(0) instanceof YAMLPlainTextImpl) {
                YAMLPlainTextImpl textImpl = (YAMLPlainTextImpl) keyValue.getYAMLElements().get(0);
                String text = textImpl.getTextValue();

                // So yaml is a bit weird and this is literally the official regex for matching boolean values in yaml.....
                if (text.matches("y|Y|yes|Yes|YES|n|N|no|No|NO|true|True|TRUE|false|False|FALSE|on|On|ON|off|Off|OFF")) {
                    if (text.matches("y|Y|yes|Yes|YES|true|True|TRUE|on|On|ON")) {
                        setValueInConfig(name, true, false);
                    } else {
                        setValueInConfig(name, false, false);
                    }
                } else {
                    setValueInConfig(name, text, isEnum);
                }
            }
        }

        // TODO: Handle outlier cases
    }

    private void handleListValue(String name, YAMLPsiElement keyValue) {
        if (keyValue.getYAMLElements().size() != 1) {
            // TODO: Show warning to user, should be YAMLBlockMappingImpl YAMLArrayImpl child
            return;
        }

        keyValue = keyValue.getYAMLElements().get(0);

        if (!(keyValue instanceof YAMLBlockMappingImpl) && !(keyValue instanceof YAMLArrayImpl)) {
            // TODO: Show warning to user, should be YAMLBlockMappingImpl or YAMLArrayImpl child
            return;
        }

        List<String> text = keyValue.getYAMLElements().stream().map(s -> ((YAMLPlainTextImpl) s.getYAMLElements().get(0)).getTextValue()).collect(Collectors.toList());
        setValueInConfig(name, text, false);
    }

    private boolean setValueInConfig(String name, Object value, boolean isEnum) {
        try {
            Field field = PluginConfig.class.getDeclaredField(name);
            field.setAccessible(true);

            if (isEnum) {
                field.set(config, Enum.valueOf(field.getType().asSubclass(Enum.class), value.toString()));
            } else {
                field.set(config, value);
            }
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
            return false;
        } catch (IllegalAccessException e) {
            // This shouldn't happen, as we are setting it to accessible before doing anything
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // Temp method for testing purposes
    private void printYamlEles(List<YAMLPsiElement> elementList, int indent) {
        elementList.forEach(e -> {
            for (int i = 0; i < indent; i++) {
                System.out.print("    ");
            }
            System.out.println(e.getClass().getSimpleName());
            if (e.getYAMLElements().size() != 0)
                printYamlEles(e.getYAMLElements(), indent + 1);
            if (e instanceof YAMLScalarList || e instanceof YAMLScalarText)
                System.out.println(e.getText());
        });
    }
}
