package example.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static us.abstracta.jmeter.javadsl.JmeterDsl.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import common.Json;
import common.TestResources;
import example.flows.UserFlows;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.configs.DslCsvDataSet.Sharing;

class CsvTemplateFlowTest {
  @Test
  void sharesRowsWithinFlowsAndCorrelatesUploadsAcrossIterations() throws Exception {
    List<JsonNode> orders = new CopyOnWriteArrayList<>();
    List<String> uploads = new CopyOnWriteArrayList<>();
    Map<String, String> customers = new ConcurrentHashMap<>();
    AtomicInteger ids = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      String response;
      if (path.equals("/api/orders")) {
        JsonNode order = Json.read(new String(exchange.getRequestBody().readAllBytes(),
            StandardCharsets.UTF_8), JsonNode.class);
        orders.add(order);
        String id = "ord-" + ids.incrementAndGet();
        customers.put(id, order.get("customer_id").asText());
        response = "{\"order_id\":\"" + id + "\"}";
      } else if (path.endsWith("/documents")) {
        String id = path.split("/")[3];
        String mimeId = exchange.getRequestHeaders().getFirst("mimeContentId");
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        uploads.add(customers.get(id) + ":" + mimeId + ":" + bytes.length);
        response = Json.write(Map.of("document_id", "doc-1", "mime_content_id", mimeId));
      } else {
        response = "{\"sku\":\"item-1001\"}";
      }
      byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
    });
    server.start();
    try {
      var stats = testPlan(
          httpDefaults().url("http://127.0.0.1:" + server.getAddress().getPort()),
          threadGroup("buyers", 2, 1,
              csvDataSet(TestResources.path("example/data/buyers.csv"))
                  .sharedIn(Sharing.THREAD_GROUP),
              UserFlows.placeOrdersFromCsv()),
          threadGroup("uploaders", 1, 2,
              csvDataSet(TestResources.path("example/data/uploaders.csv"))
                  .sharedIn(Sharing.THREAD_GROUP),
              UserFlows.placeOrderWithDocumentFromCsv())
      ).run();
      assertThat(stats.overall().errorsCount()).isZero();
      assertThat(orders).hasSize(6);
      for (String customer : List.of("customer-123", "customer-456")) {
        List<JsonNode> customerOrders = orders.stream()
            .filter(o -> o.get("customer_id").asText().equals(customer))
            .collect(java.util.stream.Collectors.toList());
        assertThat(customerOrders).hasSize(3);
        int quantity = customer.equals("customer-123") ? 2 : 3;
        for (JsonNode order : customerOrders) {
          assertThat(order.at("/lines/0/quantity").intValue())
              .isEqualTo(order.get("priority").booleanValue() ? quantity - 1 : quantity);
        }
      }
      int fileSize = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
          TestResources.path("example/uploads/scan.bin"))).length;
      assertThat(uploads).containsExactlyInAnyOrder(
          "customer-123:scan-001:" + fileSize, "customer-456:scan-002:" + fileSize);
    } finally {
      server.stop(0);
    }
  }
}
