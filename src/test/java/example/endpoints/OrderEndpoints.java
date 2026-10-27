package example.endpoints;

import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jsonExtractor;
import static example.endpoints.Vars.ORDER_ID;

import org.apache.http.entity.ContentType;
import us.abstracta.jmeter.javadsl.http.DslHttpSampler;
import example.api.model.CreateOrderRequestDto;
import example.api.model.CreateOrderResponseDto;
import common.Json;
import common.JsonTemplates;
import us.abstracta.jmeter.javadsl.core.controllers.DslTransactionController;
import common.TestResources;
import example.payloads.Payloads;

public final class OrderEndpoints {

  public static final String MIME_CONTENT_ID_HEADER = "mimeContentId";

  private OrderEndpoints() {
  }

  public static DslHttpSampler createOrder() {
    return createOrder(Payloads.createOrder());
  }

  /**
   * Creates an order and stores its id in {@link Vars#ORDER_ID}.
   */
  public static DslHttpSampler createOrder(CreateOrderRequestDto request) {
    return httpSampler("POST /api/orders", "/api/orders")
        .post(Json.write(request), ContentType.APPLICATION_JSON)
        .children(
            Json.matchesType(CreateOrderResponseDto.class),
            jsonExtractor(ORDER_ID, CreateOrderResponseDto.JSON_PROPERTY_ORDER_ID)
        );
  }

  /** Reads the template path and its values from the current thread's CSV row at runtime. */
  public static DslHttpSampler createOrderFromCsv(String templateVariable) {
    return httpSampler("POST /api/orders", "/api/orders")
        .post(s -> JsonTemplates.render(s.vars.get(templateVariable), s.vars::get),
            ContentType.APPLICATION_JSON)
        .children(
            Json.matchesType(CreateOrderResponseDto.class),
            jsonExtractor(ORDER_ID, CreateOrderResponseDto.JSON_PROPERTY_ORDER_ID)
        );
  }

  /** Uploads the CSV-selected file, correlating chunks with the first response's mimeContentId. */
  public static DslTransactionController uploadDocumentFromCsv() {
    return DocumentUploads.upload(v -> TestResources.path(v.get("UPLOAD_FILE")), DocumentUploads.CHUNK_BYTES);
  }

  /** Uploads a classpath resource as one request or sequential 100 MB chunks. */
  public static DslTransactionController uploadDocument(String resourcePath) {
    return DocumentUploads.upload(v -> TestResources.path(resourcePath), DocumentUploads.CHUNK_BYTES);
  }

}
