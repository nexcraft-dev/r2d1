package dev.nexcraft.r2d1.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the non-blank collection name used for a document type. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Document {

  /**
   * Returns the collection name.
   *
   * @return non-blank collection name
   */
  String value();
}
