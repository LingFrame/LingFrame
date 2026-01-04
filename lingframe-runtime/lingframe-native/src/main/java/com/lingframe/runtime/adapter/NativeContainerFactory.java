package com.lingframe.runtime.adapter;

import com.lingframe.api.config.PluginDefinition;
import com.lingframe.core.loader.PluginManifestLoader;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.PluginContainer;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

@Slf4j
public class NativeContainerFactory implements ContainerFactory {

    @Override
    public PluginContainer create(String pluginId, File sourceFile, ClassLoader classLoader) {
        PluginDefinition definition = PluginManifestLoader.parseDefinition(sourceFile);
        if (definition == null) {
            log.error("[{}] Cannot parse PluginDefinition from source: {}", pluginId, sourceFile);
            throw new IllegalArgumentException("PluginDefinition not found for plugin: " + pluginId);
        }
        String mainClassName = definition.getMainClass();
        if (mainClassName == null || mainClassName.isBlank()) {
            log.error("[{}] Cannot resolve Main-Class from source: {}", pluginId, sourceFile);
            throw new IllegalArgumentException("Main-Class not found for plugin: " + pluginId);
        }

        try {
            Class<?> mainClass = classLoader.loadClass(mainClassName);
            return new NativePluginContainer(pluginId, mainClass, classLoader, sourceFile);
        } catch (ClassNotFoundException e) {
            log.error("[{}] Main-Class {} not found in classpath", pluginId, mainClassName);
            throw new RuntimeException("Invalid Main-Class: " + mainClassName, e);
        } catch (Exception e) {
            log.error("[{}] Failed to create native container", pluginId, e);
            throw new RuntimeException(e);
        }
    }

}