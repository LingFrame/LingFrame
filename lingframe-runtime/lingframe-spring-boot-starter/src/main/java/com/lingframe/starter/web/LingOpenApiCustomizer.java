package com.lingframe.starter.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
public class LingOpenApiCustomizer implements LingOpenApiCustomizerAdapter {

    private static final String GROUPED_PROCESSED_EXTENSION = "x-ling-processed";
    private static final int MAX_GROUP_CONFIG_SCAN = 256;

    private final WebInterfaceManager webInterfaceManager;
    private final Environment environment;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public LingOpenApiCustomizer(WebInterfaceManager webInterfaceManager) {
        this(webInterfaceManager, null);
    }

    public LingOpenApiCustomizer(WebInterfaceManager webInterfaceManager, Environment environment) {
        this.webInterfaceManager = webInterfaceManager;
        this.environment = environment;
    }

    @Override
    public void customise(OpenAPI openApi) {
        GroupRule groupRule = resolveRequestedGroupRule();
        if (groupRule != null) {
            customise(openApi,
                    groupRule.pathsToMatch,
                    groupRule.packagesToScan,
                    groupRule.pathsToExclude,
                    groupRule.packagesToExclude);
            return;
        }
        customise(openApi, null);
    }

    @Override
    public void customise(OpenAPI openApi, Collection<String> pathsToMatch) {
        customise(openApi, pathsToMatch, null, null, null);
    }

    @Override
    public void customise(OpenAPI openApi,
                          Collection<String> pathsToMatch,
                          Collection<String> packagesToScan,
                          Collection<String> pathsToExclude,
                          Collection<String> packagesToExclude) {
        boolean isGlobal = (pathsToMatch == null || pathsToMatch.isEmpty())
                && (packagesToScan == null || packagesToScan.isEmpty());

        if (!isGlobal
                && openApi.getExtensions() != null
                && openApi.getExtensions().containsKey(GROUPED_PROCESSED_EXTENSION)) {
            return;
        }

        Map<String, List<WebInterfaceMetadata>> metadataMap = webInterfaceManager.getMetadataMap();
        if (metadataMap.isEmpty()) {
            return;
        }

        if (!isGlobal) {
            removeMismatchedLingPaths(
                    openApi, metadataMap, pathsToMatch, packagesToScan, pathsToExclude, packagesToExclude);
        }

        for (List<WebInterfaceMetadata> metadataList : metadataMap.values()) {
            if (metadataList == null || metadataList.isEmpty()) {
                continue;
            }
            for (WebInterfaceMetadata metadata : metadataList) {
                if (!matches(metadata, pathsToMatch, packagesToScan, pathsToExclude, packagesToExclude)) {
                    continue;
                }
                try {
                    addPathMapping(openApi, metadata);
                } catch (Exception ex) {
                    log.warn("Failed to inject LingFrame route [{}]: {}",
                            metadata.getUrlPattern(), ex.getMessage());
                }
            }
        }

        if (!isGlobal) {
            openApi.addExtension(GROUPED_PROCESSED_EXTENSION, true);
        }
    }

    private void removeMismatchedLingPaths(OpenAPI openApi,
                                           Map<String, List<WebInterfaceMetadata>> metadataMap,
                                           Collection<String> pathsToMatch,
                                           Collection<String> packagesToScan,
                                           Collection<String> pathsToExclude,
                                           Collection<String> packagesToExclude) {
        Paths paths = openApi.getPaths();
        if (paths == null || paths.isEmpty()) {
            return;
        }

        for (List<WebInterfaceMetadata> metadataList : metadataMap.values()) {
            if (metadataList == null || metadataList.isEmpty()) {
                continue;
            }
            for (WebInterfaceMetadata metadata : metadataList) {
                if (matches(metadata, pathsToMatch, packagesToScan, pathsToExclude, packagesToExclude)) {
                    continue;
                }
                String templatePath = toTemplatePath(metadata.getUrlPattern());
                if (templatePath != null) {
                    paths.remove(templatePath);
                }
            }
        }

        if (paths.isEmpty()) {
            openApi.setPaths(new Paths());
        }
    }

    private boolean matches(WebInterfaceMetadata metadata,
                            Collection<String> pathsToMatch,
                            Collection<String> packagesToScan,
                            Collection<String> pathsToExclude,
                            Collection<String> packagesToExclude) {
        String path = metadata.getUrlPattern();
        String className = metadata.getTargetClassName();

        if (pathsToExclude != null && !pathsToExclude.isEmpty()) {
            for (String pattern : pathsToExclude) {
                if (matchesPattern(pattern, path)) {
                    return false;
                }
            }
        }

        if (packagesToExclude != null && !packagesToExclude.isEmpty()) {
            for (String pkg : packagesToExclude) {
                if (className != null && className.startsWith(pkg)) {
                    return false;
                }
            }
        }

        boolean pathMatched = true;
        if (pathsToMatch != null && !pathsToMatch.isEmpty()) {
            pathMatched = false;
            for (String pattern : pathsToMatch) {
                if (matchesPattern(pattern, path)) {
                    pathMatched = true;
                    break;
                }
            }
        }

        boolean packageMatched = true;
        if (packagesToScan != null && !packagesToScan.isEmpty()) {
            packageMatched = false;
            for (String pkg : packagesToScan) {
                if (className != null && className.startsWith(pkg)) {
                    packageMatched = true;
                    break;
                }
            }
        }

        return pathMatched && packageMatched;
    }

    private void addPathMapping(OpenAPI openApi, WebInterfaceMetadata metadata) {
        String originalPath = metadata.getUrlPattern();
        Method targetMethod = metadata.getTargetMethod();
        if (originalPath == null || targetMethod == null) {
            return;
        }

        Paths paths = openApi.getPaths();
        if (paths == null) {
            paths = new Paths();
            openApi.setPaths(paths);
        }

        String templatePath = toTemplatePath(originalPath);
        if (templatePath == null) {
            return;
        }

        PathItem pathItem = paths.getOrDefault(templatePath, new PathItem());
        Operation operation = new Operation();

        String tagName = metadata.getLingId();
        String tagDescription = "";

        try {
            Class<?> controllerClass = targetMethod.getDeclaringClass();
            io.swagger.v3.oas.annotations.tags.Tag tagAnn =
                    AnnotatedElementUtils.findMergedAnnotation(
                            controllerClass, io.swagger.v3.oas.annotations.tags.Tag.class);
            if (tagAnn != null) {
                if (!tagAnn.name().isEmpty()) {
                    tagName = tagAnn.name();
                }
                if (!tagAnn.description().isEmpty()) {
                    tagDescription = tagAnn.description();
                }
            }
        } catch (Throwable ignored) {
        }

        String summary = metadata.getOpSummary() != null && !metadata.getOpSummary().isEmpty()
                ? metadata.getOpSummary()
                : metadata.getTargetMethodName();
        String description = metadata.getOpDescription() != null && !metadata.getOpDescription().isEmpty()
                ? metadata.getOpDescription()
                : "";

        if (summary.equals(metadata.getTargetMethodName()) && metadata.getVersion() != null) {
            summary += " [" + metadata.getVersion() + "]";
        }

        operation.setSummary(summary);
        if (!description.isEmpty()) {
            operation.setDescription(description);
        }

        if (metadata.getOpTags() != null && metadata.getOpTags().length > 0) {
            for (String tag : metadata.getOpTags()) {
                operation.addTagsItem(tag);
            }
        } else {
            operation.addTagsItem(tagName);
        }

        processParameters(operation, targetMethod, templatePath);

        operation.setOperationId("ling_"
                + metadata.getLingId().replace("-", "_")
                + "_"
                + metadata.getTargetMethodName()
                + "_"
                + Math.abs(metadata.buildRouteKey().hashCode()));

        ApiResponses responses = new ApiResponses();
        ApiResponse okResponse = new ApiResponse().description("OK (Delegated to LingFrame)");
        Content content = new Content();
        content.addMediaType("*/*",
                new MediaType().schema(
                        new Schema<>().type("object").description("Dynamic response from Ling")));
        okResponse.setContent(content);
        responses.addApiResponse("200", okResponse);
        operation.setResponses(responses);

        String method = metadata.getHttpMethod();
        if ("GET".equalsIgnoreCase(method)) {
            pathItem.setGet(operation);
        } else if ("POST".equalsIgnoreCase(method)) {
            pathItem.setPost(operation);
        } else if ("PUT".equalsIgnoreCase(method)) {
            pathItem.setPut(operation);
        } else if ("DELETE".equalsIgnoreCase(method)) {
            pathItem.setDelete(operation);
        } else if ("PATCH".equalsIgnoreCase(method)) {
            pathItem.setPatch(operation);
        }

        openApi.getPaths().addPathItem(templatePath, pathItem);

        if (openApi.getTags() == null) {
            openApi.setTags(new ArrayList<>());
        }
        String finalTagName = tagName;
        String finalTagDesc = tagDescription;
        if (openApi.getTags().stream().noneMatch(t -> t.getName().equals(finalTagName))) {
            openApi.addTagsItem(new Tag().name(finalTagName).description(finalTagDesc));
        }
    }

    private String toTemplatePath(String originalPath) {
        if (originalPath == null) {
            return null;
        }
        if (originalPath.endsWith("/**")) {
            return originalPath.substring(0, originalPath.length() - 3) + "/{path}";
        }
        if (originalPath.endsWith("/*")) {
            return originalPath.substring(0, originalPath.length() - 2) + "/{path}";
        }
        return originalPath;
    }

    private String toRegex(String antPattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < antPattern.length(); index++) {
            char current = antPattern.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < antPattern.length() && antPattern.charAt(index + 1) == '*';
                if (doubleStar) {
                    regex.append(".*");
                    index++;
                } else {
                    regex.append("[^/]*");
                }
                continue;
            }
            if ("\\.[]{}()+-^$|".indexOf(current) >= 0) {
                regex.append('\\');
            }
            regex.append(current);
        }
        regex.append('$');
        return regex.toString();
    }

    private boolean matchesPattern(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        if (pathMatcher.match(pattern, path)) {
            return true;
        }
        return Pattern.matches(toRegex(pattern), path);
    }

    private boolean isGroupedApiDocRequest() {
        return resolveRequestedGroup() != null;
    }

    private String resolveRequestedGroup() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        try {
            Method getRequest = attributes.getClass().getMethod("getRequest");
            Object request = getRequest.invoke(attributes);
            if (request == null) {
                return null;
            }
            Method getRequestUri = request.getClass().getMethod("getRequestURI");
            Object uriValue = getRequestUri.invoke(request);
            if (!(uriValue instanceof String)) {
                return null;
            }
            String uri = (String) uriValue;
            if (!uri.startsWith("/v3/api-docs/")
                    || "/v3/api-docs/swagger-config".equals(uri)) {
                return null;
            }
            return uri.substring("/v3/api-docs/".length());
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private GroupRule resolveRequestedGroupRule() {
        String group = resolveRequestedGroup();
        if (group == null || environment == null) {
            return null;
        }
        for (int index = 0; index < MAX_GROUP_CONFIG_SCAN; index++) {
            String prefix = "springdoc.group-configs[" + index + "]";
            String configuredGroup = environment.getProperty(prefix + ".group");
            if (configuredGroup == null) {
                continue;
            }
            if (!configuredGroup.equals(group)) {
                continue;
            }
            return new GroupRule(
                    listProperty(prefix + ".paths-to-match"),
                    listProperty(prefix + ".packages-to-scan"),
                    listProperty(prefix + ".paths-to-exclude"),
                    listProperty(prefix + ".packages-to-exclude"));
        }
        return null;
    }

    private List<String> listProperty(String key) {
        String[] values = environment.getProperty(key, String[].class);
        if (values != null && values.length > 0) {
            List<String> list = new ArrayList<>(values.length);
            for (String value : values) {
                if (value != null && !value.isEmpty()) {
                    list.add(value);
                }
            }
            return list.isEmpty() ? null : list;
        }
        String single = environment.getProperty(key);
        if (single == null || single.isEmpty()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (String part : single.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list.isEmpty() ? null : list;
    }

    private static final class GroupRule {
        private final Collection<String> pathsToMatch;
        private final Collection<String> packagesToScan;
        private final Collection<String> pathsToExclude;
        private final Collection<String> packagesToExclude;

        private GroupRule(Collection<String> pathsToMatch,
                          Collection<String> packagesToScan,
                          Collection<String> pathsToExclude,
                          Collection<String> packagesToExclude) {
            this.pathsToMatch = pathsToMatch;
            this.packagesToScan = packagesToScan;
            this.pathsToExclude = pathsToExclude;
            this.packagesToExclude = packagesToExclude;
        }
    }

    private void processParameters(Operation operation, Method method, String path) {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Class<?>[] parameterTypes = method.getParameterTypes();

        for (int index = 0; index < parameterAnnotations.length; index++) {
            for (Annotation annotation : parameterAnnotations[index]) {
                String annotationName = annotation.annotationType().getName();

                if ("org.springframework.web.bind.annotation.RequestBody".equals(annotationName)) {
                    RequestBody requestBody = new RequestBody();
                    requestBody.setRequired(true);

                    Content requestBodyContent = new Content();
                    Class<?> parameterType = parameterTypes[index];
                    Schema<?> schema = new Schema<>();

                    if (Collection.class.isAssignableFrom(parameterType) || parameterType.isArray()) {
                        schema.setType("array");
                        schema.setItems(new Schema<>().type("string"));
                    } else {
                        schema.setType("object");
                    }

                    requestBodyContent.addMediaType(
                            "application/json", new MediaType().schema(schema));
                    requestBody.setContent(requestBodyContent);
                    operation.setRequestBody(requestBody);
                }

                if ("org.springframework.web.bind.annotation.PathVariable".equals(annotationName)
                        && path.contains("{path}")) {
                    Parameter parameter = new Parameter();
                    parameter.setName("path");
                    parameter.setIn("path");
                    parameter.setRequired(true);
                    parameter.setSchema(new Schema<>().type("string"));
                    operation.addParametersItem(parameter);
                }
            }
        }
    }
}
