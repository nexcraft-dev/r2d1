package dev.nexcraft.r2d1.r2;

import java.net.URI;
import java.util.Objects;

/**
 * Immutable configuration required to connect to Cloudflare R2.
 *
 * <p>The endpoint is supplied explicitly as an account-specific R2 HTTPS endpoint. Credentials are
 * passed to a static AWS SDK credentials provider. The result of {@link #toString()} does not
 * expose the endpoint, bucket, or credential values.
 *
 * @param endpoint R2 HTTPS endpoint containing a host
 * @param accessKeyId R2 access key ID
 * @param secretAccessKey R2 secret access key
 * @param bucketName R2 bucket that stores documents
 * @param region region used by the AWS SDK to sign requests
 */
public record R2Config(
    URI endpoint, String accessKeyId, String secretAccessKey, String bucketName, String region) {

  private static final String DEFAULT_REGION = "auto";

  /**
   * Creates R2 configuration using the default {@code auto} region.
   *
   * @param endpoint R2 HTTPS endpoint containing a host
   * @param accessKeyId R2 access key ID
   * @param secretAccessKey R2 secret access key
   * @param bucketName R2 bucket that stores documents
   */
  public R2Config(URI endpoint, String accessKeyId, String secretAccessKey, String bucketName) {
    this(endpoint, accessKeyId, secretAccessKey, bucketName, DEFAULT_REGION);
  }

  /** Creates validated R2 configuration. */
  public R2Config {
    requireEndpoint(endpoint);
    accessKeyId = requireText(accessKeyId, "accessKeyId");
    secretAccessKey = requireText(secretAccessKey, "secretAccessKey");
    bucketName = requireText(bucketName, "bucketName");
    region = requireText(region, "region");
  }

  @Override
  public String toString() {
    return "R2Config[endpoint=<redacted>, accessKeyId=<redacted>, "
        + "secretAccessKey=<redacted>, bucketName=<redacted>, region="
        + region
        + "]";
  }

  private static void requireEndpoint(URI endpoint) {
    Objects.requireNonNull(endpoint, "endpoint");
    String path = endpoint.getRawPath();
    boolean validPath = path == null || path.isEmpty() || path.equals("/");
    if (!endpoint.isAbsolute()
        || !"https".equalsIgnoreCase(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null
        || !validPath) {
      throw new IllegalArgumentException("endpoint must be an HTTPS origin URI");
    }
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
