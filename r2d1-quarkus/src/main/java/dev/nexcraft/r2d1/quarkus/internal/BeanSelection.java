package dev.nexcraft.r2d1.quarkus.internal;

import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Singleton;
import java.lang.annotation.Annotation;
import java.util.Optional;
import java.util.stream.Collectors;

/** Selects caller-owned CDI resources without creating ambiguous or unrequested alternatives. */
@Singleton
public final class BeanSelection {
  private final Instance<Object> beans;
  private final BeanManager manager;

  /** Keeps dynamic dependent lookups associated with the CDI-managed selection bean. */
  public BeanSelection(@Any final Instance<Object> beans, final BeanManager manager) {
    this.beans = beans;
    this.manager = manager;
  }

  /** Returns a default-qualified bean when resolvable; ambiguity is a configuration error. */
  public <T> Optional<T> optional(final Class<T> type) {
    final Instance<T> candidates = beans.select(type, Default.Literal.INSTANCE);
    if (candidates.isUnsatisfied()) return Optional.empty();
    if (candidates.isAmbiguous()) throw ambiguous(type);
    return Optional.of(candidates.get());
  }

  /**
   * Requires a default-qualified resource or a resource selected by its exact CDI/datasource name.
   */
  public <T> T required(final Class<T> type, final Optional<String> name) {
    if (name.isPresent()) return named(type, name.get());
    return optional(type)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No "
                        + type.getSimpleName()
                        + " bean exists; provide an application CDI bean"));
  }

  private <T> T named(final Class<T> type, final String name) {
    final var matches =
        manager.getBeans(type, Any.Literal.INSTANCE).stream()
            .filter(bean -> named(bean, name))
            .collect(Collectors.toSet());
    if (matches.isEmpty()) {
      throw new IllegalStateException("No named " + type.getSimpleName() + " bean exists");
    }
    final Bean<?> selected;
    try {
      selected = manager.resolve(matches);
    } catch (final AmbiguousResolutionException failure) {
      throw ambiguous(type);
    }
    if (selected == null)
      throw new IllegalStateException("No named " + type.getSimpleName() + " bean exists");
    // Select the exact qualifiers before resolving alternatives; Instance retains dependent
    // lifecycles.
    return beans.select(type, selected.getQualifiers().toArray(Annotation[]::new)).get();
  }

  private static boolean named(final Bean<?> bean, final String name) {
    if (name.equals(bean.getName())) return true;
    return bean.getQualifiers().stream().anyMatch(qualifier -> datasource(qualifier, name));
  }

  private static boolean datasource(final Annotation qualifier, final String name) {
    if (!qualifier.annotationType().getName().equals("io.quarkus.agroal.DataSource")) return false;
    try {
      return name.equals(qualifier.annotationType().getMethod("value").invoke(qualifier));
    } catch (final ReflectiveOperationException failure) {
      throw new IllegalStateException("Cannot read Quarkus datasource qualifier", failure);
    }
  }

  private static IllegalStateException ambiguous(final Class<?> type) {
    return new IllegalStateException(
        "Multiple "
            + type.getSimpleName()
            + " beans exist; select an exact resource name or a CDI alternative");
  }
}
