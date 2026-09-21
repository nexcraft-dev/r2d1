package dev.nexcraft.r2d1.spring.internal;

import dev.nexcraft.r2d1.filesystem.FileSystemDocumentStore;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spring.R2D1FilesystemProperties;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the optional filesystem document component without managing its executor. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "dev.nexcraft.r2d1.filesystem.FileSystemDocumentStore")
@ConditionalOnProperty(prefix = "r2d1.document", name = "type", havingValue = "filesystem")
public class FilesystemConfiguration {

  @Bean(destroyMethod = "")
  @ConditionalOnMissingBean(DocumentStore.class)
  FileSystemDocumentStore fileSystemDocumentStore(
      R2D1FilesystemProperties properties,
      ListableBeanFactory beanFactory,
      ObjectProvider<Executor> executors) {
    Path rootDirectory =
        ConfigurationSupport.requirePath(
            properties.rootDirectory(), "r2d1.filesystem.root-directory");
    Executor executor =
        BeanSelection.required(
            beanFactory,
            Executor.class,
            executors,
            properties.executor(),
            "Executor",
            "r2d1.filesystem.executor");
    return new FileSystemDocumentStore(rootDirectory, executor);
  }
}
