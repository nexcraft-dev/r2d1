package dev.nexcraft.r2d1.r2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.URI;
import org.junit.jupiter.api.Test;

class R2ConfigTest {

  private static final URI ENDPOINT = URI.create("https://account-id.r2.cloudflarestorage.com");

  @Test
  void defaultsRegionToAutoAndPreservesValues() {
    R2Config config = new R2Config(ENDPOINT, "access", "secret", "documents");

    assertThat(config.endpoint()).isEqualTo(ENDPOINT);
    assertThat(config.accessKeyId()).isEqualTo("access");
    assertThat(config.secretAccessKey()).isEqualTo("secret");
    assertThat(config.bucketName()).isEqualTo("documents");
    assertThat(config.region()).isEqualTo("auto");
  }

  @Test
  void acceptsAnExplicitRegion() {
    R2Config config = new R2Config(ENDPOINT, "access", "secret", "documents", "custom");

    assertThat(config.region()).isEqualTo("custom");
  }

  @Test
  void rejectsInvalidEndpointsWithoutEchoingTheirValues() {
    URI[] invalidEndpoints = {
      URI.create("http://account-id.r2.cloudflarestorage.com"),
      URI.create("relative"),
      URI.create("https://user@example.com"),
      URI.create("https://example.com/path"),
      URI.create("https://example.com?query=value"),
      URI.create("https://example.com#fragment")
    };

    for (URI invalidEndpoint : invalidEndpoints) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> new R2Config(invalidEndpoint, "access", "secret", "documents"))
          .withMessage("endpoint must be an HTTPS origin URI")
          .withMessageNotContaining(invalidEndpoint.toString());
    }
  }

  @Test
  void acceptsAnHttpsOriginWithRootPathAndPort() {
    URI endpoint = URI.create("https://example.com:8443/");

    assertThat(new R2Config(endpoint, "access", "secret", "documents").endpoint())
        .isEqualTo(endpoint);
  }

  @Test
  void rejectsNullAndBlankConfigurationValues() {
    assertThatNullPointerException()
        .isThrownBy(() -> new R2Config(null, "access", "secret", "documents"))
        .withMessage("endpoint");
    assertThatNullPointerException()
        .isThrownBy(() -> new R2Config(ENDPOINT, null, "secret", "documents"))
        .withMessage("accessKeyId");
    assertThatNullPointerException()
        .isThrownBy(() -> new R2Config(ENDPOINT, "access", null, "documents"))
        .withMessage("secretAccessKey");
    assertThatNullPointerException()
        .isThrownBy(() -> new R2Config(ENDPOINT, "access", "secret", null))
        .withMessage("bucketName");
    assertThatNullPointerException()
        .isThrownBy(() -> new R2Config(ENDPOINT, "access", "secret", "documents", null))
        .withMessage("region");

    assertBlankValue("accessKeyId", () -> new R2Config(ENDPOINT, " ", "secret", "documents"));
    assertBlankValue("secretAccessKey", () -> new R2Config(ENDPOINT, "access", "\t", "documents"));
    assertBlankValue("bucketName", () -> new R2Config(ENDPOINT, "access", "secret", "\n"));
    assertBlankValue("region", () -> new R2Config(ENDPOINT, "access", "secret", "documents", " "));
  }

  @Test
  void redactsConnectionAndCredentialValuesFromToString() {
    R2Config config =
        new R2Config(ENDPOINT, "access-value", "secret-value", "private-bucket", "auto");

    assertThat(config.toString())
        .contains("endpoint=<redacted>", "region=auto")
        .doesNotContain(
            ENDPOINT.toString(), "account-id", "access-value", "secret-value", "private-bucket");
  }

  private static void assertBlankValue(String name, Runnable construction) {
    assertThatIllegalArgumentException()
        .isThrownBy(construction::run)
        .withMessage(name + " must not be blank");
  }
}
