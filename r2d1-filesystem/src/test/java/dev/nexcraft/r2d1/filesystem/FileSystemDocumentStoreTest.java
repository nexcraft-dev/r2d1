package dev.nexcraft.r2d1.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.spi.AdmissionRejectedException;
import dev.nexcraft.r2d1.spi.DocumentCursor;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.StorageException;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@NullMarked
class FileSystemDocumentStoreTest {

  private static final DocumentKey KEY = new DocumentKey("users", "user-1");

  @TempDir Path root;

  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    executor = Executors.newFixedThreadPool(4);
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  @Test
  void writesAndReplacesOneCanonicalDocument() throws IOException {
    FileSystemDocumentStore store = store();

    await(store.put(KEY, bytes("first")));
    await(store.put(KEY, bytes("second")));

    assertThat(await(store.get(KEY))).isEqualTo(bytes("second"));
    assertThat(fileNames(root.resolve("users"))).containsExactly("user-1.r2d1");
  }

  @Test
  void boundsActiveAndPendingFilesystemOperations() {
    ManualExecutor controlledExecutor = new ManualExecutor();
    FileSystemDocumentStore store =
        new FileSystemDocumentStore(root, controlledExecutor, new BackpressureConfig(1, 1));

    CompletableFuture<?> active = store.put(KEY, bytes("active")).toCompletableFuture();
    CompletableFuture<StoredDocument> pending = store.get(KEY).toCompletableFuture();
    Throwable rejected = failure(store.delete(KEY));

    assertThat(controlledExecutor.pendingTasks()).isEqualTo(1);
    assertThat(rejected).isInstanceOf(AdmissionRejectedException.class);

    controlledExecutor.runNext();
    assertThat(active).isCompleted();
    assertThat(pending).isNotCompleted();
    assertThat(controlledExecutor.pendingTasks()).isEqualTo(1);

    controlledExecutor.runNext();
    assertThat(pending).isCompleted();
    assertThat(await(pending)).isEqualTo(bytes("active"));
  }

  @Test
  void cancellationDoesNotReleaseAdmissionUntilFilesystemWorkTerminates() {
    ManualExecutor controlledExecutor = new ManualExecutor();
    FileSystemDocumentStore store =
        new FileSystemDocumentStore(root, controlledExecutor, new BackpressureConfig(1, 0));
    CompletableFuture<?> cancelled = store.put(KEY, bytes("written")).toCompletableFuture();

    assertThat(cancelled.cancel(true)).isTrue();
    assertThat(failure(store.get(KEY))).isInstanceOf(AdmissionRejectedException.class);
    assertThat(controlledExecutor.pendingTasks()).isEqualTo(1);

    controlledExecutor.runNext();
    CompletableFuture<StoredDocument> admitted = store.get(KEY).toCompletableFuture();
    assertThat(controlledExecutor.pendingTasks()).isEqualTo(1);
    controlledExecutor.runNext();

    assertThat(await(admitted)).isEqualTo(bytes("written"));
  }

  @Test
  void mapsExecutorRejectionToStorageUnavailable() {
    Executor rejectingExecutor =
        command -> {
          throw new RejectedExecutionException("executor is full");
        };
    FileSystemDocumentStore store =
        new FileSystemDocumentStore(root, rejectingExecutor, new BackpressureConfig(1, 0));

    Throwable failure = failure(store.put(KEY, bytes("unwritten")));

    assertThat(failure).isInstanceOf(StorageException.Unavailable.class);
    assertThat(failure.getCause()).isInstanceOf(RejectedExecutionException.class);
  }

  @Test
  void keepsTheCallerOwnedExecutorOpen() {
    FileSystemDocumentStore store = store();

    await(store.put(KEY, bytes("borrowed")));

    assertThat(executor.isShutdown()).isFalse();
  }

  @Test
  void createsTheRootDirectoryOnTheFirstWrite() {
    Path notYetCreated = root.resolve("nested").resolve("store");
    FileSystemDocumentStore store = new FileSystemDocumentStore(notYetCreated, executor);

    await(store.put(KEY, bytes("created")));

    assertThat(await(store.get(KEY))).isEqualTo(bytes("created"));
  }

  @Test
  void listsOnlyCommittedDocumentsWithBoundedOpaqueCursors() throws IOException {
    FileSystemDocumentStore store = store();
    for (int index = 0; index < 205; index++) {
      await(store.put(new DocumentKey("users", "user-" + index), bytes("value-" + index)));
    }
    Files.writeString(root.resolve("users").resolve("notes.txt"), "ignored");
    Files.writeString(root.resolve("users").resolve(".user-999.tmp"), "ignored");

    List<DocumentKey> listed = new ArrayList<>();
    @Nullable DocumentCursor cursor = null;
    DocumentPage page;
    do {
      page = await(store.list("users", cursor, 100));
      assertThat(page.documentKeys()).hasSizeLessThanOrEqualTo(100);
      listed.addAll(page.documentKeys());
      cursor = page.nextCursor().orElse(null);
    } while (cursor != null);

    assertThat(listed).hasSize(205);
    assertThat(new HashSet<>(listed)).hasSize(205);
    assertThat(listed).isSortedAccordingTo(java.util.Comparator.comparing(DocumentKey::id));
  }

  @Test
  void ignoresTemporaryFilesAndReturnsNotFoundWithoutACanonicalFile() throws IOException {
    FileSystemDocumentStore store = store();
    Path collection = root.resolve("users");
    Files.createDirectories(collection);
    Files.writeString(collection.resolve(".user-1.orphan.tmp"), "partial");

    assertThat(await(store.list("users", null, 10).thenApply(DocumentPage::documentKeys)))
        .isEmpty();
    assertThat(failure(store.get(KEY))).isInstanceOf(DocumentNotFoundException.class);
  }

  @Test
  void encodesTraversalSeparatorsAbsoluteNamesAndReservedComponents() throws IOException {
    FileSystemDocumentStore store = store();
    DocumentKey unsafe = new DocumentKey("../absolute\\collection", "../../CON");
    DocumentKey lowercase = new DocumentKey("users", "case");
    DocumentKey uppercase = new DocumentKey("users", "Case");

    await(store.put(unsafe, bytes("safe")));
    await(store.put(lowercase, bytes("lower")));
    await(store.put(uppercase, bytes("upper")));

    assertThat(await(store.get(unsafe))).isEqualTo(bytes("safe"));
    assertThat(
            await(store.list(unsafe.collection(), null, 10).thenApply(DocumentPage::documentKeys)))
        .containsExactly(unsafe);
    assertThat(await(store.list("users", null, 10).thenApply(DocumentPage::documentKeys)))
        .containsExactlyInAnyOrder(lowercase, uppercase);
    try (Stream<Path> paths = Files.walk(root)) {
      assertThat(paths.allMatch(path -> path.toAbsolutePath().normalize().startsWith(root)))
          .isTrue();
    }
  }

  @Test
  void readersSeeTheOldCanonicalDocumentWhileAReplacementIsBeingWritten() throws Exception {
    FileSystemDocumentStore initial = store();
    await(initial.put(KEY, bytes("old")));
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    FileSystemDocumentStore replacing =
        new FileSystemDocumentStore(
            root,
            executor,
            (path, content) -> {
              writeStarted.countDown();
              try {
                if (!releaseWrite.await(2, TimeUnit.SECONDS)) {
                  throw new IOException("test writer timed out");
                }
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("test writer interrupted", failure);
              }
              Files.write(path, content, StandardOpenOption.WRITE);
            },
            (source, target) ->
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING));

    CompletableFuture<?> replacement = replacing.put(KEY, bytes("new")).toCompletableFuture();
    assertThat(writeStarted.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(await(initial.get(KEY))).isEqualTo(bytes("old"));
    releaseWrite.countDown();
    replacement.get(2, TimeUnit.SECONDS);
    assertThat(await(initial.get(KEY))).isEqualTo(bytes("new"));
  }

  @Test
  void deleteDuringTemporaryWriteDoesNotExposePartialContent() throws Exception {
    FileSystemDocumentStore initial = store();
    await(initial.put(KEY, bytes("old")));
    CountDownLatch writeStarted = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    FileSystemDocumentStore replacing =
        new FileSystemDocumentStore(
            root,
            executor,
            (path, content) -> {
              writeStarted.countDown();
              try {
                if (!releaseWrite.await(2, TimeUnit.SECONDS)) {
                  throw new IOException("test writer timed out");
                }
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("test writer interrupted", failure);
              }
              Files.write(path, content, StandardOpenOption.WRITE);
            },
            (source, target) ->
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING));

    CompletableFuture<?> replacement = replacing.put(KEY, bytes("new")).toCompletableFuture();
    assertThat(writeStarted.await(2, TimeUnit.SECONDS)).isTrue();
    await(initial.delete(KEY));
    assertThat(failure(initial.get(KEY))).isInstanceOf(DocumentNotFoundException.class);

    releaseWrite.countDown();
    replacement.get(2, TimeUnit.SECONDS);
    assertThat(await(initial.get(KEY))).isEqualTo(bytes("new"));
  }

  @Test
  void failedTemporaryWritePreservesTheCanonicalDocumentAndCleansUp() throws IOException {
    FileSystemDocumentStore initial = store();
    await(initial.put(KEY, bytes("old")));
    FileSystemDocumentStore failing =
        new FileSystemDocumentStore(
            root,
            executor,
            (path, content) -> {
              throw new IOException("test write failure");
            },
            (source, target) -> {
              throw new AssertionError("publication must not start after a write failure");
            });

    Throwable failure = failure(failing.put(KEY, bytes("new")));

    assertThat(failure)
        .isInstanceOf(RuntimeException.class)
        .hasMessage("Filesystem put failed for document: users/user-1");
    assertThat(await(initial.get(KEY))).isEqualTo(bytes("old"));
    assertThat(fileNames(root.resolve("users"))).containsExactly("user-1.r2d1");
  }

  @Test
  void unsupportedAtomicPublicationPreservesTheCanonicalDocumentAndCleansUp() throws IOException {
    FileSystemDocumentStore initial = store();
    await(initial.put(KEY, bytes("old")));
    FileSystemDocumentStore failing =
        new FileSystemDocumentStore(
            root,
            executor,
            (path, content) -> Files.write(path, content, StandardOpenOption.WRITE),
            (source, target) -> {
              throw new AtomicMoveNotSupportedException("source", "target", "unsupported");
            });

    Throwable failure = failure(failing.put(KEY, bytes("new")));

    assertThat(failure).isInstanceOf(dev.nexcraft.r2d1.spi.StorageException.Operation.class);
    assertThat(failure.getCause()).isInstanceOf(AtomicMoveNotSupportedException.class);
    assertThat(await(initial.get(KEY))).isEqualTo(bytes("old"));
    assertThat(fileNames(root.resolve("users"))).containsExactly("user-1.r2d1");
  }

  @Test
  void concurrentPutsExposeOnlyCompleteDocuments() throws Exception {
    FileSystemDocumentStore store = store();
    List<CompletableFuture<?>> writes = new ArrayList<>();
    Set<String> values = new HashSet<>();
    for (int index = 0; index < 32; index++) {
      String value = "value-" + index + "-" + "x".repeat(2048);
      values.add(value);
      writes.add(store.put(KEY, bytes(value)).toCompletableFuture());
    }

    CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

    String stored = new String(await(store.get(KEY)).content(), StandardCharsets.UTF_8);
    assertThat(values).contains(stored);
  }

  @Test
  void deleteIsIdempotentAndSurvivesReopen() throws IOException {
    FileSystemDocumentStore store = store();
    await(store.delete(KEY));
    await(store.put(KEY, bytes("persistent")));
    assertThat(await(new FileSystemDocumentStore(root, executor).get(KEY)))
        .isEqualTo(bytes("persistent"));

    await(store.delete(KEY));
    await(store.delete(KEY));
    assertThat(failure(store.get(KEY))).isInstanceOf(DocumentNotFoundException.class);
  }

  @Test
  void rejectsMalformedFilesystemCursorsWithoutReadingAnotherPath() {
    Throwable failure =
        failure(store().list("users", new dev.nexcraft.r2d1.spi.DocumentCursor("../"), 10));

    assertThat(failure)
        .isInstanceOf(dev.nexcraft.r2d1.spi.StorageException.Operation.class)
        .hasMessage("Filesystem list failed");
  }

  private FileSystemDocumentStore store() {
    return new FileSystemDocumentStore(root, executor);
  }

  private static StoredDocument bytes(String value) {
    return new StoredDocument(value.getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> fileNames(Path directory) throws IOException {
    try (Stream<Path> paths = Files.list(directory)) {
      return paths.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
    try {
      return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError("test interrupted", failure);
    } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
      throw new AssertionError(
          "stage failed", failure.getCause() == null ? failure : failure.getCause());
    }
  }

  private static Throwable failure(java.util.concurrent.CompletionStage<?> stage) {
    try {
      stage.toCompletableFuture().join();
      throw new AssertionError("stage unexpectedly succeeded");
    } catch (CompletionException failure) {
      return failure.getCause();
    }
  }

  private static final class ManualExecutor implements Executor {

    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable command) {
      tasks.add(command);
    }

    private synchronized int pendingTasks() {
      return tasks.size();
    }

    private void runNext() {
      Runnable task;
      synchronized (this) {
        task = tasks.remove();
      }
      task.run();
    }
  }
}
