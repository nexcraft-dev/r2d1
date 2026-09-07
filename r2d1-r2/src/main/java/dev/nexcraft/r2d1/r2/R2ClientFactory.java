package dev.nexcraft.r2d1.r2;

import java.util.Objects;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Creates an asynchronous AWS SDK S3 client configured for Cloudflare R2 compatibility. */
final class R2ClientFactory {

  private R2ClientFactory() {}

  static S3AsyncClient create(R2Config config) {
    Objects.requireNonNull(config, "config");
    return S3AsyncClient.builder()
        .endpointOverride(config.endpoint())
        .region(Region.of(config.region()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKeyId(), config.secretAccessKey())))
        .serviceConfiguration(serviceConfiguration())
        .httpClientBuilder(NettyNioAsyncHttpClient.builder())
        .build();
  }

  static S3Configuration serviceConfiguration() {
    return S3Configuration.builder()
        .pathStyleAccessEnabled(true)
        .chunkedEncodingEnabled(false)
        .build();
  }
}
