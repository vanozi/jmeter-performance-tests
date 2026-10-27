package example.endpoints;

import static org.assertj.core.api.Assertions.*;
import static us.abstracta.jmeter.javadsl.JmeterDsl.*;

import com.sun.net.httpserver.HttpServer;
import common.Json;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentUploadsTest {
  @TempDir Path directory;

  @Test
  void sendsExactChunksAndReusesFirstIdForEachFile() throws Exception {
    assertThat(DocumentUploads.CHUNK_BYTES).isEqualTo(100_000_000L);
    for (int size : new int[]{0, 3, 4, 5, 8, 10}) {
      Path file = directory.resolve("file-" + size);
      byte[] content = new byte[size];
      for (int i = 0; i < size; i++) content[i] = (byte) i;
      Files.write(file, content);
      List<byte[]> bodies = new CopyOnWriteArrayList<>();
      List<String> headers = new CopyOnWriteArrayList<>();
      HttpServer server = server(bodies, headers, 0, false);
      try {
        var stats = testPlan(
            httpDefaults().url("http://127.0.0.1:" + server.getAddress().getPort()),
            vars().set(Vars.ACCESS_TOKEN, "upload-test-token"),
            threadGroup(1, 2, DocumentUploads.upload(v -> file.toString(), 4))
        ).run();
        assertThat(stats.overall().errorsCount()).isZero();
        int count = Math.max(1, (size + 3) / 4);
        assertThat(bodies).hasSize(count * 2);
        for (int iteration = 0; iteration < 2; iteration++) {
          var joined = new java.io.ByteArrayOutputStream();
          for (int i = 0; i < count; i++) {
            int index = iteration * count + i;
            assertThat(bodies.get(index).length).isLessThanOrEqualTo(4);
            joined.write(bodies.get(index));
            assertThat(headers.get(index)).isEqualTo(i == 0 ? "<absent>" : "mime-" + (iteration * count));
          }
          assertThat(joined.toByteArray()).isEqualTo(content);
        }
      } finally { server.stop(0); }
    }
  }

  @Test
  void keepsUploadIdsIsolatedBetweenConcurrentUsers() throws Exception {
    Path fileA = directory.resolve("a");
    Path fileB = directory.resolve("b");
    Files.write(fileA, "AAAAAAAAAA".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    Files.write(fileB, "BBBBBBBBBB".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    List<String> received = new CopyOnWriteArrayList<>();
    List<String> authorizations = new CopyOnWriteArrayList<>();
    Map<String, String> ids = new java.util.concurrent.ConcurrentHashMap<>();
    var sequence = new java.util.concurrent.atomic.AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/orders/", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      String user = Character.toString((char) body[0]);
      authorizations.add(user + ":" + exchange.getRequestHeaders().getFirst("Authorization"));
      String header = exchange.getRequestHeaders().getFirst("mimeContentId");
      String expected;
      if (header == null) {
        expected = user + "-" + sequence.incrementAndGet();
        ids.put(user, expected);
      } else {
        expected = ids.get(user);
      }
      received.add(user + ":" + (header == null ? "first" : header.equals(expected) ? "ok" : "wrong"));
      byte[] response = Json.write(Map.of("document_id", "doc", "mimeContentId", expected))
          .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(201, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      var stats = testPlan(
          httpDefaults().url("http://127.0.0.1:" + server.getAddress().getPort()),
          threadGroup("a", 1, 2,
              jsr223Sampler(s -> { s.vars.put(Vars.ACCESS_TOKEN, "token-a"); s.sampleResult.setIgnore(); }),
              DocumentUploads.upload(v -> fileA.toString(), 4)),
          threadGroup("b", 1, 2,
              jsr223Sampler(s -> { s.vars.put(Vars.ACCESS_TOKEN, "token-b"); s.sampleResult.setIgnore(); }),
              DocumentUploads.upload(v -> fileB.toString(), 4))
      ).run();
      assertThat(stats.overall().errorsCount()).isZero();
      assertThat(received).hasSize(12);
      assertThat(authorizations).hasSize(12).containsOnly("A:Bearer token-a", "B:Bearer token-b");
      for (String user : List.of("A", "B")) {
        assertThat(received.stream().filter(r -> r.equals(user + ":first")).count()).isEqualTo(2);
        assertThat(received.stream().filter(r -> r.equals(user + ":ok")).count()).isEqualTo(4);
      }
    } finally { server.stop(0); }
  }

  @Test
  void stopsAfterFailedChunkOrMissingFirstId() throws Exception {
    Path file = directory.resolve("large");
    Files.write(file, new byte[13]);
    for (boolean missingId : new boolean[]{false, true}) {
      List<byte[]> bodies = new CopyOnWriteArrayList<>();
      List<String> headers = new CopyOnWriteArrayList<>();
      HttpServer server = server(bodies, headers, missingId ? 0 : 2, missingId);
      try {
        var stats = testPlan(
            httpDefaults().url("http://127.0.0.1:" + server.getAddress().getPort()),
            vars().set(Vars.ACCESS_TOKEN, "upload-test-token"),
            threadGroup(1, 1, DocumentUploads.upload(v -> file.toString(), 4))
        ).run();
        assertThat(stats.overall().errorsCount()).isGreaterThan(0);
        assertThat(bodies).hasSize(missingId ? 1 : 2);
      } finally { server.stop(0); }
    }
  }

  @Test
  void rejectsMissingOrBlankTokenBeforeSendingFile() throws Exception {
    Path file = directory.resolve("authenticated-file");
    Files.write(file, new byte[8]);
    for (String token : new String[]{"", "   "}) {
      List<byte[]> bodies = new CopyOnWriteArrayList<>();
      HttpServer server = server(bodies, new CopyOnWriteArrayList<>(), 0, false);
      try {
        var stats = testPlan(
            httpDefaults().url("http://127.0.0.1:" + server.getAddress().getPort()),
            vars().set(Vars.ACCESS_TOKEN, token),
            threadGroup(1, 1, DocumentUploads.upload(v -> file.toString(), 4))
        ).run();
        assertThat(stats.overall().errorsCount()).isGreaterThan(0);
        assertThat(bodies).isEmpty();
      } finally { server.stop(0); }
    }
  }

  private HttpServer server(List<byte[]> bodies, List<String> headers, int failAt,
      boolean missingId) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/orders/", exchange -> {
      int index = bodies.size();
      bodies.add(exchange.getRequestBody().readAllBytes());
      String header = exchange.getRequestHeaders().getFirst("mimeContentId");
      headers.add(header == null ? "<absent>" : header);
      // Subsequent responses deliberately return different IDs: the first ID must remain in use.
      byte[] response = Json.write(missingId ? Map.of("document_id", "doc")
          : Map.of("document_id", "doc", "mimeContentId", "mime-" + index)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      boolean authorized = "Bearer upload-test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.sendResponseHeaders(!authorized ? 401 : bodies.size() == failAt ? 500 : 201, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    return server;
  }
}
