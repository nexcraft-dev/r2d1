package dev.nexcraft.r2d1.r2;

import dev.nexcraft.r2d1.BackpressureConfig;
import java.util.Objects;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Creates an asynchronous AWS SDK S3 client configured for Cloudflare R2 compatibility. */
final class R2ClientFactory {

  private static final Logger LOGGER = Logger.getLogger(R2ClientFactory.class.getName());

  private R2ClientFactory() {}

  static S3AsyncClient create(R2Config config) {
    return create(config, BackpressureConfig.DEFAULT, null);
  }

  static S3AsyncClient create(
      R2Config config,
      BackpressureConfig backpressureConfig,
      @Nullable Integer clientMaxConcurrency) {
    Objects.requireNonNull(config, "config");
    int maxConcurrency = resolveClientMaxConcurrency(backpressureConfig, clientMaxConcurrency);
    return S3AsyncClient.builder()
        .endpointOverride(config.endpoint())
        .region(Region.of(config.region()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey())))
        .serviceConfiguration(serviceConfiguration())
        .httpClientBuilder(NettyNioAsyncHttpClient.builder().maxConcurrency(maxConcurrency))
        .build();
  }

  static int resolveClientMaxConcurrency(
      BackpressureConfig backpressureConfig, @Nullable Integer clientMaxConcurrency) {
    int admissionMaxConcurrency =
        Objects.requireNonNull(backpressureConfig, "backpressureConfig").maxConcurrency();
    if (clientMaxConcurrency == null) {
      return admissionMaxConcurrency;
    }
    if (clientMaxConcurrency <= 0) {
      throw new IllegalArgumentException("clientMaxConcurrency must be greater than zero");
    }
    if (clientMaxConcurrency < admissionMaxConcurrency) {
      LOGGER.warning(
          "Explicit R2 S3 client maxConcurrency "
              + clientMaxConcurrency
              + " is below R2 admission maxConcurrency "
              + admissionMaxConcurrency
              + "; the AWS client may queue admitted requests.");
    }
    return clientMaxConcurrency;
  }

  static S3Configuration serviceConfiguration() {
    return S3Configuration.builder()
        .pathStyleAccessEnabled(true)
        .chunkedEncodingEnabled(false)
        .build();
  }
}
