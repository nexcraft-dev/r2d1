package dev.nexcraft.r2d1.quarkus.deployment;

import dev.nexcraft.r2d1.quarkus.R2D1BuildTimeConfig;
import dev.nexcraft.r2d1.quarkus.internal.BeanSelection;
import dev.nexcraft.r2d1.quarkus.internal.D1Producer;
import dev.nexcraft.r2d1.quarkus.internal.OwnedResources;
import dev.nexcraft.r2d1.quarkus.internal.R2D1Producer;
import dev.nexcraft.r2d1.quarkus.internal.R2Producer;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import java.util.Set;

/** Declares R2D1 extension integration during Quarkus augmentation. */
public final class R2D1Processor {
  /** Reports the installed extension without creating runtime storage resources. */
  @BuildStep
  FeatureBuildItem feature() {
    return new FeatureBuildItem("r2d1");
  }

  /** Registers runtime producers only for enabled applications. */
  @BuildStep
  AdditionalBeanBuildItem beans(final R2D1BuildTimeConfig config) {
    if (!config.enabled()) return AdditionalBeanBuildItem.builder().build();
    final var builder =
        AdditionalBeanBuildItem.builder()
            .addBeanClasses(
                BeanSelection.class,
                OwnedResources.class,
                R2D1Producer.class,
                R2Producer.class,
                D1Producer.class)
            .setUnremovable();
    if (QuarkusClassLoader.isClassPresentAtRuntime("dev.nexcraft.r2d1.jdbc.JdbcIndexStore")) {
      builder.addBeanClass("dev.nexcraft.r2d1.quarkus.internal.JdbcProducer");
    }
    if (QuarkusClassLoader.isClassPresentAtRuntime(
        "dev.nexcraft.r2d1.filesystem.FileSystemDocumentStore")) {
      builder.addBeanClass("dev.nexcraft.r2d1.quarkus.internal.FilesystemProducer");
    }
    return builder.build();
  }

  /** Retains only resource types that are resolved dynamically by this integration. */
  @BuildStep
  UnremovableBeanBuildItem resources(final R2D1BuildTimeConfig config) {
    return new UnremovableBeanBuildItem(
        bean ->
            config.enabled()
                && bean.getTypes().stream()
                    .anyMatch(
                        type ->
                            Set.of(
                                        "dev.nexcraft.r2d1.R2D1",
                                        "dev.nexcraft.r2d1.R2D1$CollectionFactory",
                                        "dev.nexcraft.r2d1.DocumentCodec",
                                        "dev.nexcraft.r2d1.spi.DocumentStore",
                                        "dev.nexcraft.r2d1.spi.IndexStore",
                                        "dev.nexcraft.r2d1.PersistenceCollectionFactory$CollectionInitializer",
                                        "dev.nexcraft.r2d1.jdbc.JdbcExecution")
                                    .contains(type.name().toString())
                                || type.name().toString().equals("java.util.concurrent.Executor")
                                || type.name().toString().equals("javax.sql.DataSource")
                                || type.name().toString().equals("java.net.http.HttpClient")
                                || type.name()
                                    .toString()
                                    .equals("software.amazon.awssdk.services.s3.S3AsyncClient")));
  }

  /** Registers only the optional datasource qualifier method used for named selection. */
  @BuildStep
  void datasourceQualifier(
      final R2D1BuildTimeConfig config, final BuildProducer<ReflectiveClassBuildItem> reflection) {
    if (config.enabled()
        && QuarkusClassLoader.isClassPresentAtRuntime("io.quarkus.agroal.DataSource")) {
      reflection.produce(
          ReflectiveClassBuildItem.builder("io.quarkus.agroal.DataSource")
              .methods()
              .reason("R2D1 named datasource selection")
              .build());
    }
  }
}
