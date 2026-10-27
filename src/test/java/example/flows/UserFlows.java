package example.flows;

import static us.abstracta.jmeter.javadsl.JmeterDsl.onceOnlyController;
import static us.abstracta.jmeter.javadsl.JmeterDsl.transaction;

import us.abstracta.jmeter.javadsl.core.controllers.DslOnceOnlyController;
import us.abstracta.jmeter.javadsl.core.controllers.DslTransactionController;
import example.endpoints.AuthEndpoints;
import example.endpoints.ItemEndpoints;
import example.endpoints.OrderEndpoints;
import example.payloads.Payloads;

/**
 * Business steps built from endpoint samplers. Each transaction also gets its own entry in the
 * stats, so you can assert on a whole step as well as on single requests.
 */
public final class UserFlows {

  private UserFlows() {
  }

  /**
   * Gets an access token on every iteration.
   */
  public static DslTransactionController login() {
    return login(AuthEndpoints.DEFAULT_USERNAME, AuthEndpoints.DEFAULT_PASSWORD);
  }

  public static DslTransactionController login(String username, String password) {
    return transaction("login",
        AuthEndpoints.getToken(username, password, null)
    );
  }

  /**
   * Gets an access token only on the first iteration of each thread, like a user who logs in
   * once and then keeps working. Put it first in the thread group.
   */
  public static DslOnceOnlyController loginOnce() {
    return onceOnlyController(
        login()
    );
  }

  public static DslTransactionController browseItem() {
    return browseItem(Payloads.DEFAULT_ITEM_ID);
  }

  public static DslTransactionController browseItem(Object itemId) {
    return transaction("browse item",
        ItemEndpoints.getItem(itemId, true)
    );
  }

  public static DslTransactionController placeOrder() {
    return transaction("place order",
        ItemEndpoints.getItem(Payloads.DEFAULT_ITEM_ID, false),
        OrderEndpoints.createOrder()
    );
  }

  /** All requests use the row loaded at the start of this thread-group iteration. */
  public static DslTransactionController placeOrdersFromCsv() {
    return transaction("place orders from CSV",
        ItemEndpoints.getItem(common.JmeterVars.ref("ITEM_ID"), false),
        OrderEndpoints.createOrderFromCsv("ORDER_TEMPLATE"),
        OrderEndpoints.createOrderFromCsv("PRIORITY_ORDER_TEMPLATE")
    );
  }

  public static DslTransactionController placeOrderWithDocumentFromCsv() {
    return transaction("place order with document",
        OrderEndpoints.createOrderFromCsv("ORDER_TEMPLATE"),
        OrderEndpoints.uploadDocumentFromCsv()
    );
  }

  public static DslTransactionController placePriorityOrder() {
    return transaction("place priority order",
        OrderEndpoints.createOrder(Payloads.createOrder().priority(true))
    );
  }

  /**
   * Creates an order and uploads a file to it, using the order id from the first call.
   *
   * @param resourcePath file path relative to src/test/resources, e.g. {@code example/uploads/scan.bin}
   */
  public static DslTransactionController placeOrderWithDocument(String resourcePath) {
    return transaction("place order with document",
        OrderEndpoints.createOrder(),
        OrderEndpoints.uploadDocument(resourcePath)
    );
  }

}
