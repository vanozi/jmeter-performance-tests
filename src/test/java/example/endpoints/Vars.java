package example.endpoints;

/**
 * Names of JMeter variables passed between samplers. Extractors write them and other samplers
 * read them, so keeping the names here prevents typos between the two sides.
 */
public final class Vars {

  public static final String ACCESS_TOKEN = "ACCESS_TOKEN";
  public static final String ITEM_ID = "ITEM_ID";
  public static final String ORDER_ID = "ORDER_ID";
  public static final String MIME_CONTENT_ID = "MIME_CONTENT_ID";

  private Vars() {
  }

}
