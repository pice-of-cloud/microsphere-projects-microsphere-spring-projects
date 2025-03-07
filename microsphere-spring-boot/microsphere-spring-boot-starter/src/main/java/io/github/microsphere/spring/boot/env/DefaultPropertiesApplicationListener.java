package io.github.microsphere.spring.boot.env;

import io.github.microsphere.spring.boot.util.SpringApplicationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ObjectUtils;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.springframework.core.io.support.SpringFactoriesLoader.loadFactories;

/**
 * Listable {@link ApplicationEnvironmentPreparedEvent} {@link ApplicationListener} Class
 * {@link SpringApplication#setDefaultProperties(Properties) "defaultProperties"}
 *
 * @author <a href="mailto:mercyblitz@gmail.com">Mercy<a/>
 * @see SpringApplication#setDefaultProperties(Properties)
 * @see DefaultPropertiesPostProcessor
 * @since 1.0.0
 */
public class DefaultPropertiesApplicationListener implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    public static final String DEFAULT_PROPERTIES_PROPERTY_SOURCE_NAME = "defaultProperties";

    public static final int DEFAULT_ORDER = LoggingApplicationListener.LOWEST_PRECEDENCE - 1;

    private static final Logger logger = LoggerFactory.getLogger(DefaultPropertiesApplicationListener.class);

    private int order = DEFAULT_ORDER;

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        ConfigurableEnvironment environment = event.getEnvironment();
        SpringApplication springApplication = event.getSpringApplication();
        processDefaultProperties(environment, springApplication);
    }

    private void processDefaultProperties(ConfigurableEnvironment environment, SpringApplication springApplication) {
        Map<String, Object> defaultProperties = getDefaultProperties(environment);
        if (defaultProperties != null) {
            postProcessDefaultProperties(springApplication, defaultProperties);
            logDefaultProperties(springApplication, defaultProperties);
        }
    }

    Map<String, Object> getDefaultProperties(ConfigurableEnvironment environment) {
        Map<String, Object> defaultProperties = null;
        MapPropertySource defaultPropertiesPropertySource = getOrCreateDefaultPropertiesPropertySource(environment);
        if (defaultPropertiesPropertySource != null) {
            defaultProperties = defaultPropertiesPropertySource.getSource();
            logger.debug("The 'defaultProperties' property was obtained successfully, and the current content is: {}", defaultProperties);
        }
        return defaultProperties;
    }

    private void postProcessDefaultProperties(SpringApplication springApplication, Map<String, Object> defaultProperties) {
        ResourceLoader resourceLoader = SpringApplicationUtils.getResourceLoader(springApplication);
        ClassLoader classLoader = resourceLoader.getClassLoader();
        List<PropertySourceLoader> propertySourceLoaders = loadFactories(PropertySourceLoader.class, classLoader);
        List<DefaultPropertiesPostProcessor> defaultPropertiesPostProcessors = loadFactories(DefaultPropertiesPostProcessor.class, classLoader);
        // DefaultPropertiesPostProcessor execute
        for (DefaultPropertiesPostProcessor defaultPropertiesPostProcessor : defaultPropertiesPostProcessors) {
            postProcessDefaultProperties(defaultPropertiesPostProcessor, propertySourceLoaders, resourceLoader, defaultProperties);
        }

        // Compatible SpringApplicationUtilsgetDefaultPropertiesResources way
        loadDefaultPropertiesResources(propertySourceLoaders, resourceLoader, defaultProperties);
    }

    private void loadDefaultPropertiesResources(List<PropertySourceLoader> propertySourceLoaders,
                                                ResourceLoader resourceLoader,
                                                Map<String, Object> defaultProperties) {
        Set<String> defaultPropertiesResources = SpringApplicationUtils.getDefaultPropertiesResources();
        logger.debug("Start loading from SpringApplicationUtils. GetDefaultPropertiesResources () 'defaultProperties resources: {}", defaultPropertiesResources);
        loadDefaultProperties(defaultPropertiesResources, propertySourceLoaders, resourceLoader, defaultProperties);
    }

    private void postProcessDefaultProperties(DefaultPropertiesPostProcessor defaultPropertiesPostProcessor,
                                              List<PropertySourceLoader> propertySourceLoaders,
                                              ResourceLoader resourceLoader,
                                              Map<String, Object> defaultProperties) {
        Set<String> defaultPropertiesResources = new LinkedHashSet<>();

        String processorClassName = defaultPropertiesPostProcessor.getClass().getName();

        logger.debug("DefaultPropertiesPostProcessor '{}' start processing 'defaultProperties: {}", processorClassName, defaultPropertiesResources);
        defaultPropertiesPostProcessor.initializeResources(defaultPropertiesResources);

        // 记载 "defaultProperties"
        loadDefaultProperties(defaultPropertiesResources, propertySourceLoaders, resourceLoader, defaultProperties);

        defaultPropertiesPostProcessor.postProcess(defaultProperties);
        logger.debug("DefaultPropertiesPostProcessor '{}' end processing 'defaultProperties: {}", processorClassName, defaultPropertiesResources);
    }

    private void loadDefaultProperties(Collection<String> defaultPropertiesResources,
                                       List<PropertySourceLoader> propertySourceLoaders,
                                       ResourceLoader resourceLoader,
                                       Map<String, Object> defaultProperties) {
        logger.debug("Start loading the 'defaultProperties' resource path list: {}", defaultPropertiesResources);
        for (String defaultPropertiesResource : defaultPropertiesResources) {
            Resource resource = resourceLoader.getResource(defaultPropertiesResource);
            if (!resource.exists()) {
                logger.warn("'defaultProperties' resource [location: {}] does not exist, please make sure the resource is correct!", defaultPropertiesResource);
            }
            if (!loadDefaultProperties(defaultPropertiesResource, resource, propertySourceLoaders, defaultProperties)) {
                logger.warn("'defaultProperties' resource [location: {}] failed to load, please confirm the resource can be processed!", defaultPropertiesResource);
            }
        }

    }

    private boolean loadDefaultProperties(String defaultPropertiesResource, Resource resource,
                                          List<PropertySourceLoader> propertySourceLoaders,
                                          Map<String, Object> defaultProperties) {
        boolean loaded = false;
        for (PropertySourceLoader propertySourceLoader : propertySourceLoaders) {
            if (loadDefaultProperties(defaultPropertiesResource, resource, propertySourceLoader, defaultProperties)) {
                loaded = true;
                break;
            }
        }
        return loaded;
    }

    private boolean loadDefaultProperties(String defaultPropertiesResource, Resource resource,
                                          PropertySourceLoader propertySourceLoader,
                                          Map<String, Object> defaultProperties) {
        String fileExtension = getExtension(defaultPropertiesResource);
        String[] fileExtensions = propertySourceLoader.getFileExtensions();
        boolean loaded = false;
        if (matches(fileExtension, fileExtensions)) {
            try {
                List<PropertySource<?>> propertySources = propertySourceLoader.load(defaultPropertiesResource, resource);
                logger.debug("'defaultProperties' resource [location: {}] loads into {} PropertySource", defaultPropertiesResource, propertySources.size());
                for (PropertySource propertySource : propertySources) {
                    if (propertySource instanceof EnumerablePropertySource) {
                        merge((EnumerablePropertySource) propertySource, defaultProperties);
                        loaded = true;
                    }
                }
            } catch (IOException e) {
                logger.error("'defaultProperties' resource [location: {}] failed to load due to: {}",
                        defaultPropertiesResource, e.getMessage());
            }
        }
        return loaded;
    }

    private void merge(EnumerablePropertySource<?> propertySource, Map<String, Object> defaultProperties) {
        logger.debug("'defaultProperties' PropertySource[{}] Try merging!", propertySource);
        String[] propertyNames = propertySource.getPropertyNames();
        for (String propertyName : propertyNames) {
            Object propertyValue = propertySource.getProperty(propertyName);
            Object oldPropertyValue = defaultProperties.putIfAbsent(propertyName, propertyValue);
            if (oldPropertyValue == null) {
                logger.debug("'defaultProperties' attribute [name: {}, value: {}] added successfully!", propertyName, propertyValue);
            } else {
                logger.warn("'defaultProperties' attribute [name: {}, old-value: {}] already exists, new-value[{}] will not be merged!",
                        propertyName, oldPropertyValue, propertyValue);
            }
        }
    }

    private boolean matches(String fileExtension, String[] fileExtensions) {
        return ObjectUtils.containsElement(fileExtensions, fileExtension);
    }

    private String getExtension(String resourceLocation) {
        int index = resourceLocation.lastIndexOf(".");
        if (index == -1) {
            return null;
        }
        String extension = resourceLocation.substring(index + 1);
        return extension;
    }

    private MapPropertySource getOrCreateDefaultPropertiesPropertySource(ConfigurableEnvironment environment) {
        MutablePropertySources propertySources = environment.getPropertySources();
        final String name = DEFAULT_PROPERTIES_PROPERTY_SOURCE_NAME;
        PropertySource propertySource = propertySources.get(name);
        MapPropertySource defaultPropertiesPropertySource = null;
        if (propertySource == null) {
            logger.warn("SpringApplication does not initialize the 'defaultProperties' property, and will create an MapPropertySource[name:{}] by default", name);
            defaultPropertiesPropertySource = new MapPropertySource(name, new HashMap<>());
            propertySources.addLast(defaultPropertiesPropertySource);
        } else if (propertySource instanceof MapPropertySource) {
            logger.debug("SpringApplication initializes the 'defaultProperties' property");
            defaultPropertiesPropertySource = (MapPropertySource) propertySource;
        } else {
            logger.warn("'defaultProperties' PropertySource[name: {}] is not an MapPropertySource instance; it is actually: {}", name,
                    propertySource.getClass().getName());
        }
        return defaultPropertiesPropertySource;
    }

    private void logDefaultProperties(SpringApplication springApplication, Map<String, Object> defaultProperties) {
        logger.debug("SpringApplication[sources:{}]的defaultProperties:", springApplication.getSources());
        defaultProperties.forEach((key, value) -> {
            logger.debug("'{}' = {}", key, value);
        });
    }


    @Override
    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }
}
