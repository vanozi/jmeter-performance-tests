package example.endpoints;

import static us.abstracta.jmeter.javadsl.JmeterDsl.httpSampler;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jsonAssertion;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jsonExtractor;
import static example.endpoints.Vars.DOCUMENT_ID;
import static example.endpoints.Vars.ORDER_ID;
import static common.JmeterVars.ref;

import org.apache.http.entity.ContentType;
import org.apache.jmeter.protocol.http.util.HTTPConstants;
import us.abstracta.jmeter.javadsl.http.DslHttpSampler;
import example.api.model.CreateOrderRequestDto;
import example.api.model.CreateOrderResponseDto;
import example.api.model.DocumentDto;
import common.Json;
import common.JsonTemplates;
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

  /** Resolves the upload resource selected by the same CSV row as the order request. */
  public static DslHttpSampler uploadDocumentFromCsv() {
    return httpSampler("POST /api/orders/{order_id}/documents",
        s -> "/api/orders/" + s.vars.get(ORDER_ID) + "/documents")
        .method(HTTPConstants.POST)
        .contentType(ContentType.APPLICATION_OCTET_STREAM)
        .bodyFile(ref("UPLOAD_RESOURCE_PATH"))
        .header(MIME_CONTENT_ID_HEADER, ref("MIME_CONTENT_ID"))
        .children(
            us.abstracta.jmeter.javadsl.JmeterDsl.jsr223PreProcessor(s ->
                s.vars.put("UPLOAD_RESOURCE_PATH", TestResources.path(s.vars.get("UPLOAD_FILE")))),
            Json.matchesType(DocumentDto.class),
            jsonExtractor(DOCUMENT_ID, DocumentDto.JSON_PROPERTY_DOCUMENT_ID),
            jsonAssertion(DocumentDto.JSON_PROPERTY_MIME_CONTENT_ID)
                .equalsTo(ref("MIME_CONTENT_ID"))
        );
  }

  /**
   * Uploads a file to the order in {@link Vars#ORDER_ID}, without a mimeContentId header.
   *
   * @param resourcePath file path relative to src/test/resources, e.g. {@code example/uploads/scan.bin}
   */
  public static DslHttpSampler uploadDocument(String resourcePath) {
    return uploadDocument(ref(ORDER_ID), resourcePath, null);
  }

  /**
   * Uploads a file to the order in {@link Vars#ORDER_ID} with the given mimeContentId header.
   */
  public static DslHttpSampler uploadDocument(String resourcePath, String mimeContentId) {
    return uploadDocument(ref(ORDER_ID), resourcePath, mimeContentId);
  }

  /**
   * Uploads a file from src/test/resources as a raw application/octet-stream body and stores the
   * document id in {@link Vars#DOCUMENT_ID}.
   *
   * @param mimeContentId value for the optional mimeContentId header, or null to leave it out.
   *                      When set, the response must echo it.
   */
  public static DslHttpSampler uploadDocument(String orderId, String resourcePath,
      String mimeContentId) {
    DslHttpSampler sampler = httpSampler("POST /api/orders/{order_id}/documents",
        "/api/orders/" + orderId + "/documents")
        .method(HTTPConstants.POST)
        .contentType(ContentType.APPLICATION_OCTET_STREAM)
        // sends the file bytes as the whole request body
        .bodyFile(TestResources.path(resourcePath))
        .children(
            Json.matchesType(DocumentDto.class),
            jsonExtractor(DOCUMENT_ID, DocumentDto.JSON_PROPERTY_DOCUMENT_ID)
        );
    if (mimeContentId != null) {
      sampler.header(MIME_CONTENT_ID_HEADER, mimeContentId)
          .children(
              jsonAssertion(DocumentDto.JSON_PROPERTY_MIME_CONTENT_ID).equalsTo(mimeContentId)
          );
    }
    return sampler;
  }

}
