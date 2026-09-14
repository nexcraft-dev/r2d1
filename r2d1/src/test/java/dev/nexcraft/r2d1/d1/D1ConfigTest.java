package dev.nexcraft.r2d1.d1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class D1ConfigTest {

  @Test
  void preservesRequiredValues() {
    D1Config config = new D1Config("account", "database", "token");

    assertThat(config.accountId()).isEqualTo("account");
    assertThat(config.databaseId()).isEqualTo("database");
    assertThat(config.apiToken()).isEqualTo("token");
  }

  @Test
  @SuppressWarnings("DataFlowIssue")
  void rejectsNullAndBlankValues() {
    assertThatNullPointerException()
        .isThrownBy(() -> new D1Config(null, "database", "token"))
        .withMessage("accountId");
    assertThatNullPointerException()
        .isThrownBy(() -> new D1Config("account", null, "token"))
        .withMessage("databaseId");
    assertThatNullPointerException()
        .isThrownBy(() -> new D1Config("account", "database", null))
        .withMessage("apiToken");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new D1Config(" ", "database", "token"))
        .withMessage("accountId must not be blank");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new D1Config("account", "\t", "token"))
        .withMessage("databaseId must not be blank");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new D1Config("account", "database", "\n"))
        .withMessage("apiToken must not be blank");
  }

  @Test
  void redactsEveryConnectionValueFromToString() {
    D1Config config = new D1Config("account-secret", "database-secret", "token-secret");

    assertThat(config.toString())
        .contains("accountId=<redacted>", "databaseId=<redacted>", "apiToken=<redacted>")
        .doesNotContain("account-secret", "database-secret", "token-secret");
  }
}
