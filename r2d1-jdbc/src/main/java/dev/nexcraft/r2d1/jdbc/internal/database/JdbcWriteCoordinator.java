package dev.nexcraft.r2d1.jdbc.internal.database;

import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinates database writes without introducing another executor or work queue. */
public interface JdbcWriteCoordinator {

  void execute(SqlWrite write) throws SQLException;

  static JdbcWriteCoordinator direct() {
    return SqlWrite::execute;
  }

  static JdbcWriteCoordinator serial() {
    ReentrantLock lock = new ReentrantLock(true);
    return write -> {
      lock.lock();
      try {
        write.execute();
      } finally {
        lock.unlock();
      }
    };
  }

  @FunctionalInterface
  interface SqlWrite {

    void execute() throws SQLException;
  }
}
