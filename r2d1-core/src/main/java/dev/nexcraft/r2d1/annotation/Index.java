package dev.nexcraft.r2d1.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a member as available for indexed filtering and, optionally, sorting. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
public @interface Index {

  /**
   * Reports whether the indexed member may be used for sorting.
   *
   * @return {@code true} when sorting is allowed
   */
  boolean sortable() default false;
}
