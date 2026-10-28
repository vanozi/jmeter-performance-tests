package example.endpoints;

import static us.abstracta.jmeter.javadsl.JmeterDsl.*;
import static common.JmeterVars.ref;

import com.fasterxml.jackson.databind.JsonNode;
import common.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Function;
import org.apache.http.entity.ContentType;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy;
import org.apache.jmeter.threads.JMeterVariables;
import us.abstracta.jmeter.javadsl.core.controllers.DslTransactionController;

/** Sequential raw-body uploads, correlated using the first response's mimeContentId. */
final class DocumentUploads {
  static final long CHUNK_BYTES = 100_000_000L;
  private static final String STATE = "DOCUMENT_UPLOAD_STATE";
  private static final String CHUNK_PATH = "DOCUMENT_UPLOAD_CHUNK_PATH";

  private DocumentUploads() {
  }

  static DslTransactionController upload(Function<JMeterVariables, String> file, long chunkBytes) {
    if (chunkBytes <= 0) throw new IllegalArgumentException("Chunk size must be positive");
    return transaction("upload document",
        jsr223Sampler("prepare document upload", s -> {
          State previous = state(s.vars);
          if (previous != null) previous.close();
          s.vars.remove(STATE);
          s.vars.remove(Vars.MIME_CONTENT_ID);
          s.vars.remove(CHUNK_PATH);
          requireAccessToken(s.vars);
          s.vars.putObject(STATE, new State(Path.of(file.apply(s.vars)), chunkBytes));
          s.sampleResult.setIgnore();
        }),
        whileController("upload chunks", s -> state(s.vars) != null && state(s.vars).hasNext(),
            jsr223Sampler("prepare upload chunk", s -> {
              State state = state(s.vars);
              try {
                requireAccessToken(s.vars);
                s.vars.put(CHUNK_PATH, state.prepare().toString());
                s.sampleResult.setIgnore();
              } catch (Exception e) {
                state.failed = true;
                state.close();
                throw e;
              }
            }),
            ifController(s -> state(s.vars).hasNext(),
                httpSampler("POST /api/orders/{order_id}/documents",
                    s -> "/api/orders/" + s.vars.get(Vars.ORDER_ID) + "/documents")
                    .method("POST")
                    .header("Authorization", "Bearer " + ref(Vars.ACCESS_TOKEN))
                    .contentType(ContentType.APPLICATION_OCTET_STREAM)
                    .bodyFile(ref(CHUNK_PATH))
                    .children(
                        jsr223PreProcessor(s -> {
                          var headers = ((HTTPSamplerProxy) s.sampler).getHeaderManager();
                          headers.removeHeaderNamed(OrderEndpoints.MIME_CONTENT_ID_HEADER);
                          String id = state(s.vars).mimeId;
                          if (id != null) headers.add(new Header(
                              OrderEndpoints.MIME_CONTENT_ID_HEADER, id));
                        }),
                        jsr223PostProcessor(s -> {
                          State state = state(s.vars);
                          try {
                            if (!s.prev.isSuccessful()) {
                              state.failed = true;
                              return;
                            }
                            JsonNode response = Json.read(s.prev.getResponseDataAsString(), JsonNode.class);
                            if (state.offset == 0) {
                              JsonNode id = response.get("mimeContentId");
                              if (id != null && id.isTextual() && !id.asText().isBlank()) {
                                state.mimeId = id.asText();
                                s.vars.put(Vars.MIME_CONTENT_ID, state.mimeId);
                              } else if (state.size > state.chunkBytes) {
                                throw new IllegalArgumentException("First upload response is missing mimeContentId");
                              }
                            }
                            state.offset += state.currentSize;
                            state.finished = state.offset >= state.size;
                          } catch (Exception e) {
                            state.failed = true;
                            s.prev.setSuccessful(false);
                            s.prev.setResponseMessage("Upload failed: " + e.getMessage());
                          } finally {
                            if (!state.hasNext()) state.close();
                          }
                        })
                    )
            )
        )
    );
  }

  private static State state(JMeterVariables vars) {
    return (State) vars.getObject(STATE);
  }

  private static void requireAccessToken(JMeterVariables vars) {
    String token = vars.get(Vars.ACCESS_TOKEN);
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException("Upload requires ACCESS_TOKEN; log in before uploading");
    }
  }

  private static final class State {
    private final Path source;
    private final long size;
    private final long chunkBytes;
    private Path temporary;
    private long offset;
    private long currentSize;
    private String mimeId;
    private boolean finished;
    private boolean failed;

    private State(Path source, long chunkBytes) throws IOException {
      if (!Files.isRegularFile(source)) throw new IOException("Upload file not found: " + source);
      this.source = source;
      this.size = Files.size(source);
      this.chunkBytes = chunkBytes;
    }

    private boolean hasNext() {
      return !finished && !failed;
    }

    private Path prepare() throws IOException {
      currentSize = Math.min(chunkBytes, size - offset);
      if (size <= chunkBytes) return source;
      if (temporary == null) {
        temporary = Files.createTempFile("jmeter-upload-", ".chunk");
        temporary.toFile().deleteOnExit();
      }
      // Reuse one disk file per upload; never load a 100 MB chunk into the JVM heap.
      try (FileChannel channel = FileChannel.open(source, StandardOpenOption.READ);
          OutputStream output = Files.newOutputStream(temporary)) {
        channel.position(offset);
        InputStream input = Channels.newInputStream(channel);
        byte[] buffer = new byte[64 * 1024];
        long remaining = currentSize;
        while (remaining > 0) {
          int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
          if (read < 0) throw new IOException("Upload file changed while reading: " + source);
          output.write(buffer, 0, read);
          remaining -= read;
        }
      }
      return temporary;
    }

    private void close() {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException e) {
          // deleteOnExit is the fallback when the test is interrupted or a reader still holds it.
        }
      }
    }
  }
}
