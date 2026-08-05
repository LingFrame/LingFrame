package com.lingframe.starter.resource;

import com.lingframe.starter.configuration.LingFrameCoreConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableAutoConfiguration
@Import(LingFrameCoreConfiguration.class)
public class LingTestSpringConfiguration {
}
