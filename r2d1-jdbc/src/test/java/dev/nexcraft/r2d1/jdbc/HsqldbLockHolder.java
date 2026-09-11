package dev.nexcraft.r2d1.jdbc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Holds an embedded HSQLDB file open for the real file-lock translation test. */
final class HsqldbLockHolder {

  private HsqldbLockHolder() {}

  public static void main(String[] arguments) throws Exception {
    String url = arguments[0];
    try (Connection connection = DriverManager.getConnection(url, "sa", "");
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
      System.out.println("READY");
      System.out.flush();
      input.readLine();
      try (Statement statement = connection.createStatement()) {
        statement.execute("SHUTDOWN");
      }
    }
  }
}
