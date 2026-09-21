package dev.nexcraft.r2d1.spring.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

/** Deterministic selection for optional or named application beans. */
final class BeanSelection {

  private BeanSelection() {}

  static <T> T required(
      ListableBeanFactory beanFactory,
      Class<T> beanType,
      ObjectProvider<T> provider,
      @Nullable String configuredName,
      String beanDescription,
      String nameProperty) {
    if (configuredName != null) {
      String name = ConfigurationSupport.requireText(configuredName, nameProperty);
      try {
        return beanFactory.getBean(name, beanType);
      } catch (NoSuchBeanDefinitionException failure) {
        throw new IllegalStateException(
            "No "
                + beanDescription
                + " bean named '"
                + name
                + "' exists; correct "
                + nameProperty
                + " or define the named bean",
            failure);
      } catch (BeansException failure) {
        throw new IllegalStateException(
            "Bean named '" + name + "' is not a " + beanDescription, failure);
      }
    }

    T candidate = provider.getIfUnique();
    if (candidate != null) {
      return candidate;
    }
    long count = provider.stream().count();
    if (count == 0) {
      throw new IllegalStateException(
          "No " + beanDescription + " bean exists; define one before enabling R2D1");
    }
    throw new IllegalStateException(
        "Multiple "
            + beanDescription
            + " beans are available without a resolvable @Primary bean; configure "
            + nameProperty);
  }

  static <T> @Nullable T optional(ObjectProvider<T> provider, String beanDescription) {
    T candidate = provider.getIfUnique();
    if (candidate != null) {
      return candidate;
    }
    long count = provider.stream().count();
    if (count == 0) {
      return null;
    }
    throw new IllegalStateException(
        "Multiple "
            + beanDescription
            + " beans are available without a resolvable @Primary bean; configure the bean or provide the R2D1 storage bean directly");
  }
}
