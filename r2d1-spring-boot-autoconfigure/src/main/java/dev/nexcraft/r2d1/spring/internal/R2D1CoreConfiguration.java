package dev.nexcraft.r2d1.spring.internal;

import dev.nexcraft.r2d1.PersistenceCollectionFactory;
import dev.nexcraft.r2d1.R2D1;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.IndexStore;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the framework-neutral R2D1 facade from resolved application components. */
@Configuration(proxyBeanMethods = false)
public class R2D1CoreConfiguration {

  @Bean
  @ConditionalOnMissingBean(value = {R2D1.class, R2D1.CollectionFactory.class})
  PersistenceCollectionFactory persistenceCollectionFactory(
      ListableBeanFactory beanFactory,
      ObjectProvider<DocumentStore> documentStores,
      ObjectProvider<IndexStore> indexStores,
      ObjectProvider<PersistenceCollectionFactory.CollectionInitializer> initializers) {
    DocumentStore documentStore =
        BeanSelection.required(
            beanFactory,
            DocumentStore.class,
            documentStores,
            null,
            "DocumentStore",
            "r2d1.document.type");
    IndexStore indexStore =
        BeanSelection.required(
            beanFactory, IndexStore.class, indexStores, null, "IndexStore", "r2d1.index.type");
    PersistenceCollectionFactory.CollectionInitializer initializer =
        BeanSelection.required(
            beanFactory,
            PersistenceCollectionFactory.CollectionInitializer.class,
            initializers,
            null,
            "PersistenceCollectionFactory.CollectionInitializer",
            "r2d1.collection-initializer");
    return new PersistenceCollectionFactory(documentStore, indexStore, initializer);
  }

  @Bean
  @ConditionalOnMissingBean(R2D1.class)
  R2D1 r2d1(R2D1.CollectionFactory collectionFactory) {
    return R2D1.builder().collectionFactory(collectionFactory).build();
  }
}
