package dev.nexcraft.r2d1.quarkus.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** Executes the local storage contract in the Quarkus test application. */
@QuarkusTest
public class R2D1SmokeTest {
  @Test
  void persistsThroughFacade() {
    given().when().get("/smoke").then().statusCode(200).body(is("ok"));
  }
}
