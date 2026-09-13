package dev.nexcraft.r2d1.micronaut.internal;

import io.micronaut.context.BeanProvider;
import io.micronaut.context.Qualifier;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.context.exceptions.NonUniqueBeanException;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.jspecify.annotations.Nullable;

/** Deterministic selection for optional or named application beans. */
final class BeanSelection {

  private BeanSelection() {}

  static <T> T required(
      BeanProvider<T> provider,
      @Nullable String configuredName,
      String beanDescription,
      String nameProperty) {
    if (configuredName != null) {
      String name = requireText(configuredName, nameProperty);
      Qualifier<T> qualifier = Qualifiers.byName(name);
      return provider
          .find(qualifier)
          .orElseThrow(
              () ->
                  new ConfigurationException(
                      "No "
                          + beanDescription
                          + " bean named '"
                          + name
                          + "' exists; correct "
                          + nameProperty
                          + " or define the named bean"));
    }
    if (!provider.isPresent()) {
      throw new ConfigurationException(
          "No " + beanDescription + " bean exists; define one before enabling R2D1");
    }
    try {
      return provider.get();
    } catch (NonUniqueBeanException failure) {
      throw new ConfigurationException(
          "Multiple "
              + beanDescription
              + " beans are available without a resolvable primary/default bean; configure "
              + nameProperty,
          failure);
    }
  }

  static <T> @Nullable T optional(BeanProvider<T> provider, String beanDescription) {
    if (!provider.isPresent()) {
      return null;
    }
    try {
      return provider.get();
    } catch (NonUniqueBeanException failure) {
      throw new ConfigurationException(
          "Multiple "
              + beanDescription
              + " beans are available; mark one @Primary or provide the R2D1 storage bean directly",
          failure);
    }
  }

  static String requireText(@Nullable String value, String property) {
    if (value == null || value.isBlank()) {
      throw new ConfigurationException(property + " must be configured with a non-blank value");
    }
    return value;
  }
}
