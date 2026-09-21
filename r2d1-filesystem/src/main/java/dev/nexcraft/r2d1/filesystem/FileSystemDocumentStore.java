package dev.nexcraft.r2d1.filesystem;

import dev.nexcraft.r2d1.AdmissionController;
import dev.nexcraft.r2d1.BackpressureConfig;
import dev.nexcraft.r2d1.spi.DocumentCursor;
import dev.nexcraft.r2d1.spi.DocumentKey;
import dev.nexcraft.r2d1.spi.DocumentNotFoundException;
import dev.nexcraft.r2d1.spi.DocumentPage;
import dev.nexcraft.r2d1.spi.DocumentStore;
import dev.nexcraft.r2d1.spi.StorageException;
import dev.nexcraft.r2d1.spi.StoredDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;

/**
 * Asynchronous {@link DocumentStore} backed by one filesystem directory tree.
 *
 * <p>Each document has one canonical file under {@code rootDirectory}: the collection and document
 * identifier are encoded as safe path segments and the document file ends in {@code .json}. A put
 * writes a unique temporary file in the collection directory and publishes it with an atomic move
 * that replaces the canonical file. The adapter never falls back to a non-atomic move.
 *
 * <p>Filesystem calls are blocking, so every operation is submitted to the caller-owned executor.
 * The executor must be suitable for blocking filesystem work and should provide bounded admission;
 * this adapter does not create, own, or close an executor.
 *
 * <p>Atomic publication provides visibility of complete old or new files, not power-loss
 * durability. The adapter does not force file or directory metadata to stable storage. A provider
 * that cannot atomically replace the canonical file is rejected with a storage failure.
 */
public final class FileSystemDocumentStore implements DocumentStore {

  private static final String TEMP_SUFFIX = ".tmp";

  private final Path rootDirectory;
  private final Executor ioExecutor;
  private final AdmissionController admissionController;
  private final TemporaryDocumentWriter documentWriter;
  private final AtomicDocumentPublisher documentPublisher;

  /**
   * Creates a filesystem document store using the caller-owned blocking-I/O executor.
   *
   * @param rootDirectory directory containing collection directories; it is created on the first
   *     write when absent
   * @param ioExecutor executor used for every blocking filesystem operation
   * @throws NullPointerException if either argument is {@code null}
   */
  public FileSystemDocumentStore(Path rootDirectory, Executor ioExecutor) {
    this(rootDirectory, ioExecutor, BackpressureConfig.DEFAULT);
  }

  /**
   * Creates a filesystem document store using the caller-owned blocking-I/O executor and the
   * supplied admission limits.
   *
   * @param rootDirectory directory containing collection directories; it is created on the first
   *     write when absent
   * @param ioExecutor executor used for every blocking filesystem operation
   * @param backpressureConfig active and pending operation limits for this store
   * @throws NullPointerException if any argument is {@code null}
   */
  public FileSystemDocumentStore(
      Path rootDirectory, Executor ioExecutor, BackpressureConfig backpressureConfig) {
    this(
        rootDirectory,
        ioExecutor,
        FileSystemDocumentStore::writeDocument,
        FileSystemDocumentStore::moveAtomically,
        backpressureConfig);
  }

  FileSystemDocumentStore(
      Path rootDirectory,
      Executor ioExecutor,
      TemporaryDocumentWriter documentWriter,
      AtomicDocumentPublisher documentPublisher) {
    this(rootDirectory, ioExecutor, documentWriter, documentPublisher, BackpressureConfig.DEFAULT);
  }

  FileSystemDocumentStore(
      Path rootDirectory,
      Executor ioExecutor,
      TemporaryDocumentWriter documentWriter,
      AtomicDocumentPublisher documentPublisher,
      BackpressureConfig backpressureConfig) {
    this.rootDirectory =
        Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
    this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    this.admissionController =
        new AdmissionController(Objects.requireNonNull(backpressureConfig, "backpressureConfig"));
    this.documentWriter = Objects.requireNonNull(documentWriter, "documentWriter");
    this.documentPublisher = Objects.requireNonNull(documentPublisher, "documentPublisher");
  }

  @Override
  public CompletionStage<DocumentPage> list(
      String collection, @Nullable DocumentCursor cursor, int limit) {
    String validatedCollection = requireCollection(collection);
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be greater than zero");
    }
    return submit("list", null, () -> listCommittedDocuments(validatedCollection, cursor, limit));
  }

  @Override
  public CompletionStage<@Nullable Void> put(DocumentKey key, StoredDocument document) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(document, "document");
    return submit("put", key, () -> putDocument(key, document));
  }

  @Override
  public CompletionStage<StoredDocument> get(DocumentKey key) {
    Objects.requireNonNull(key, "key");
    return submit("get", key, () -> getDocument(key));
  }

  @Override
  public CompletionStage<@Nullable Void> delete(DocumentKey key) {
    Objects.requireNonNull(key, "key");
    return submit("delete", key, () -> deleteDocument(key));
  }

  private DocumentPage listCommittedDocuments(
      String collection, @Nullable DocumentCursor cursor, int limit) throws IOException {
    @Nullable String after =
        cursor == null ? null : FileSystemPath.requireCanonicalDocumentName(cursor.value());
    Path collectionDirectory = existingCollectionDirectory(collection);
    if (collectionDirectory == null) {
      return new DocumentPage(List.of(), java.util.Optional.empty());
    }

    int maximumCandidates = limit == Integer.MAX_VALUE ? Integer.MAX_VALUE : limit + 1;
    PriorityQueue<String> candidates = new PriorityQueue<>(Comparator.reverseOrder());
    try (DirectoryStream<Path> paths = Files.newDirectoryStream(collectionDirectory)) {
      for (Path path : paths) {
        String fileName = path.getFileName().toString();
        if (!FileSystemPath.isCanonicalDocumentName(fileName)
            || (after != null && fileName.compareTo(after) <= 0)
            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        candidates.add(fileName);
        if (candidates.size() > maximumCandidates) {
          candidates.poll();
        }
      }
    }

    List<String> ordered = new ArrayList<>(candidates);
    ordered.sort(Comparator.naturalOrder());
    boolean hasMore = ordered.size() > limit;
    int returnedSize = Math.min(limit, ordered.size());
    List<DocumentKey> keys = new ArrayList<>(returnedSize);
    for (int index = 0; index < returnedSize; index++) {
      keys.add(
          new DocumentKey(
              collection, FileSystemPath.documentIdFromCanonicalName(ordered.get(index))));
    }
    java.util.Optional<DocumentCursor> nextCursor =
        hasMore
            ? java.util.Optional.of(new DocumentCursor(ordered.get(returnedSize - 1)))
            : java.util.Optional.empty();
    return new DocumentPage(keys, nextCursor);
  }

  private @Nullable Void putDocument(DocumentKey key, StoredDocument document) throws IOException {
    Path collectionDirectory = ensureCollectionDirectory(key.collection());
    Path canonical = FileSystemPath.documentPath(collectionDirectory, key.id());
    @Nullable Path temporary = null;
    try {
      temporary =
          Files.createTempFile(
              collectionDirectory, FileSystemPath.temporaryPrefix(key.id()), TEMP_SUFFIX);
      documentWriter.write(temporary, document.content());
      documentPublisher.publish(temporary, canonical);
      temporary = null;
      return null;
    } catch (IOException | RuntimeException failure) {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  private StoredDocument getDocument(DocumentKey key) throws IOException {
    Path collectionDirectory = existingCollectionDirectory(key.collection());
    if (collectionDirectory == null) {
      throw new NoSuchFileException(key.id());
    }
    Path canonical = FileSystemPath.documentPath(collectionDirectory, key.id());
    if (!Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS)) {
      throw new NoSuchFileException(key.id());
    }
    try (InputStream input = Files.newInputStream(canonical, LinkOption.NOFOLLOW_LINKS)) {
      return new StoredDocument(input.readAllBytes());
    }
  }

  private @Nullable Void deleteDocument(DocumentKey key) throws IOException {
    Path collectionDirectory = existingCollectionDirectory(key.collection());
    if (collectionDirectory != null) {
      Files.deleteIfExists(FileSystemPath.documentPath(collectionDirectory, key.id()));
    }
    return null;
  }

  private Path ensureCollectionDirectory(String collection) throws IOException {
    Files.createDirectories(rootDirectory);
    ensureDirectory(rootDirectory, "root directory");
    Path collectionDirectory = FileSystemPath.collectionPath(rootDirectory, collection);
    Files.createDirectories(collectionDirectory);
    ensureDirectory(collectionDirectory, "collection directory");
    return collectionDirectory;
  }

  private @Nullable Path existingCollectionDirectory(String collection) throws IOException {
    if (!Files.exists(rootDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    ensureDirectory(rootDirectory, "root directory");
    Path collectionDirectory = FileSystemPath.collectionPath(rootDirectory, collection);
    if (!Files.exists(collectionDirectory, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    ensureDirectory(collectionDirectory, "collection directory");
    return collectionDirectory;
  }

  private static void ensureDirectory(Path path, String description) throws IOException {
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new FileSystemException(description + " is not a directory");
    }
  }

  private <T> CompletionStage<T> submit(
      String operation, @Nullable DocumentKey key, IoOperation<T> ioOperation) {
    return admissionController.submit(
        () -> {
          CompletableFuture<T> result = new CompletableFuture<>();
          try {
            ioExecutor.execute(
                () -> {
                  try {
                    result.complete(ioOperation.run());
                  } catch (IOException | RuntimeException failure) {
                    result.completeExceptionally(mapFailure(operation, key, failure));
                  }
                });
          } catch (RuntimeException failure) {
            result.completeExceptionally(mapFailure(operation, key, failure));
          }
          return result;
        });
  }

  private static RuntimeException mapFailure(
      String operation, @Nullable DocumentKey key, Throwable failure) {
    if (failure instanceof StorageException storageFailure) {
      return storageFailure;
    }
    if ("get".equals(operation) && failure instanceof NoSuchFileException && key != null) {
      DocumentNotFoundException notFound = new DocumentNotFoundException(key);
      notFound.initCause(failure);
      return notFound;
    }
    if (failure instanceof RejectedExecutionException) {
      return new StorageException.Unavailable(
          "Filesystem " + operation + " execution was rejected", failure);
    }
    if (failure instanceof AccessDeniedException || failure instanceof SecurityException) {
      return new StorageException.Access(message(operation, key), failure);
    }
    if (failure instanceof AtomicMoveNotSupportedException
        || failure instanceof FileAlreadyExistsException) {
      return new StorageException.Operation(
          "Filesystem atomic document replacement is unavailable", failure);
    }
    if (failure instanceof FileSystemException) {
      return new StorageException.Unavailable(message(operation, key), failure);
    }
    return new StorageException.Operation(message(operation, key), failure);
  }

  private static String message(String operation, @Nullable DocumentKey key) {
    if (key == null) {
      return "Filesystem " + operation + " failed";
    }
    return "Filesystem " + operation + " failed for document: " + key.collection() + "/" + key.id();
  }

  private static String requireCollection(String collection) {
    Objects.requireNonNull(collection, "collection");
    if (collection.isBlank()) {
      throw new IllegalArgumentException("collection must not be blank");
    }
    return collection;
  }

  private static void writeDocument(Path path, byte[] content) throws IOException {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(content);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
    }
  }

  private static void moveAtomically(Path source, Path target) throws IOException {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  @FunctionalInterface
  interface TemporaryDocumentWriter {

    void write(Path path, byte[] content) throws IOException;
  }

  @FunctionalInterface
  interface AtomicDocumentPublisher {

    void publish(Path source, Path target) throws IOException;
  }

  @FunctionalInterface
  private interface IoOperation<T> {

    T run() throws IOException;
  }
}
