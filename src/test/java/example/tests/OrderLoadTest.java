package example.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static us.abstracta.jmeter.javadsl.JmeterDsl.testPlan;
import static us.abstracta.jmeter.javadsl.JmeterDsl.threadGroup;
import static us.abstracta.jmeter.javadsl.JmeterDsl.csvDataSet;
import static us.abstracta.jmeter.javadsl.JmeterDsl.htmlReporter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.jtlWriter;
import static us.abstracta.jmeter.javadsl.JmeterDsl.throughputTimer;
import static us.abstracta.jmeter.javadsl.JmeterDsl.uniformRandomTimer;
import static common.JmeterVars.ref;
import static example.endpoints.Vars.ITEM_ID;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;
import common.TestConfig;
import common.TestResources;
import example.flows.UserFlows;
import us.abstracta.jmeter.javadsl.core.configs.DslCsvDataSet.Sharing;

public class OrderLoadTest {

  @Disabled("needs the test API running; start it, set -DbaseUrl if needed and remove this")
  @Test
  public void browsersAndBuyers() throws IOException {
    TestPlanStats stats = testPlan(
        TestConfig.defaults(),
        // each call to a factory method creates a new sampler, so reusing them across thread
        // groups is safe
        threadGroup("browsers")
            .rampToAndHold(10, Duration.ofSeconds(10), Duration.ofSeconds(30))
            .children(
                csvDataSet(TestResources.path("example/data/browsers.csv")).sharedIn(Sharing.THREAD_GROUP),
                // simulates a user waiting between 1 and 3 seconds before each request
                uniformRandomTimer(Duration.ofSeconds(1), Duration.ofSeconds(3)),
                UserFlows.browseItem(ref(ITEM_ID))
            ),
        threadGroup("buyers")
            .rampToAndHold(2, Duration.ofSeconds(5), Duration.ofSeconds(20))
            .children(
                csvDataSet(TestResources.path("example/data/buyers.csv")).sharedIn(Sharing.THREAD_GROUP),
                // limits all samplers in this group together to 30 samples per minute
                throughputTimer(30),
                UserFlows.login(ref("USERNAME"), ref("PASSWORD")),
                UserFlows.placeOrdersFromCsv()
            ),
        threadGroup("uploaders", 1, 5,
            csvDataSet(TestResources.path("example/data/uploaders.csv")).sharedIn(Sharing.THREAD_GROUP),
            UserFlows.login(ref("USERNAME"), ref("PASSWORD")),
            UserFlows.placeOrderWithDocumentFromCsv()
        ),
        // raw request results for analysis in JMeter or another results tool
        jtlWriter("target/jmeter-results.jtl"),
        // creates a uniquely named standalone report after the test run
        htmlReporter("target/jmeter-reports")
    ).run();

    assertThat(stats.overall().errorsCount()).isZero();
    assertThat(stats.byLabel("login").sampleTime().perc90())
        .isLessThan(Duration.ofSeconds(1));
    assertThat(stats.byLabel("place orders from CSV").sampleTime().perc90())
        .isLessThan(Duration.ofSeconds(2));
    assertThat(stats.byLabel("GET /api/items/{item_id}").sampleTime().perc90())
        .isLessThan(Duration.ofMillis(500));
    assertThat(stats.byLabel("POST /api/orders/{order_id}/documents").sampleTime().perc90())
        .isLessThan(Duration.ofSeconds(1));
  }

}
