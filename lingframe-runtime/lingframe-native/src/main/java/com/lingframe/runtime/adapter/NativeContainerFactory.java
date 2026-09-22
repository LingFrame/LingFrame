package com.lingframe.runtime.adapter;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.core.config.LingFrameInfo;
import com.lingframe.core.spi.ContainerFactory;
import com.lingframe.core.spi.LingContainer;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.core.exception.LingInstallException;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

@Slf4j
public class NativeContainerFactory implements ContainerFactory {

    private final LingFrameInfo lingFrameInfo;

    public NativeContainerFactory() {
        this(null);
    }

    public NativeContainerFactory(LingFrameInfo lingFrameInfo) {
        this.lingFrameInfo = lingFrameInfo;
    }

    @Override
    public LingContainer create(LingDefinition definition, File sourceFile, ClassLoader classLoader) {
        if (definition == null) {
            throw new InvalidArgumentException("definition", "LingDefinition cannot be null.");
        }
        String lingId = definition.getId();
        if (lingId == null || lingId.trim().isEmpty()) {
            throw new InvalidArgumentException("lingId", "Ling ID cannot be empty.");
        }
        String mainClassName = definition.getMainClass();
        if (mainClassName == null || mainClassName.trim().isEmpty()) {
            log.error("[{}] Cannot resolve Main-Class from source: {}", lingId, sourceFile);
            throw new InvalidArgumentException("mainClass", "Main-Class not found for ling: " + lingId);
        }

        try {
            Class<?> mainClass = classLoader.loadClass(mainClassName);
            return new NativeLingContainer(lingId, mainClass, classLoader, sourceFile, lingFrameInfo);
        } catch (ClassNotFoundException e) {
            log.error("[{}] Main-Class {} not found in classpath", lingId, mainClassName);
            throw new LingInstallException(lingId, "Invalid Main-Class: " + mainClassName, e);
        } catch (Exception e) {
            log.error("[{}] Failed to create native container", lingId, e);
            throw new LingInstallException(lingId, "Failed to create native container", e);
        }
    }

}
