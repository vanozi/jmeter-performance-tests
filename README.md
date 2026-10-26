# jmeter-java-dsl-sample

A starting point for API load tests with
[jmeter-java-dsl](https://abstracta.github.io/jmeter-java-dsl), where request and response
classes are generated from an OpenAPI spec.

The project has two sides:

- **`example`**: a complete, working example against a small test API (items, orders, file upload,
  form login). Read it, copy from it, delete it when you no longer need it.
- **Your own code**: empty packages and an `openapi/api.yaml` slot to build your own endpoints and
  flows in.

Check the [jmeter-java-dsl user guide](https://abstracta.github.io/jmeter-java-dsl/guide) for
everything the DSL can do.

## Pre requisites

* Java 11+
* Maven 3.5+

## Structure

```
openapi/
├── api.yaml                      ← put YOUR spec here (generated into package api.model)
└── example/test-api.yaml         spec of the example (generated into example.api.model)

src/test/java/
├── common/                       shared helpers, used by the example and by your code
│   ├── TestConfig.java           base URL (-DbaseUrl=...) and httpDefaults()
│   ├── Json.java                 DTO → request body, response type check
│   ├── TestResources.java        path of a file in src/test/resources (for uploads)
│   ├── JmeterVars.java           ref("X") → ${X}
│   └── invoker/ApiClient.java    needed by the generated classes, don't remove
│
├── endpoints/                    ┐
├── flows/                        │ YOUR code. Each package has a package-info.java
├── payloads/                     │ explaining what goes there and what to copy from
├── tests/                        ┘
│
└── example/                      THE EXAMPLE
    ├── endpoints/                one method per endpoint, returning a new sampler
    ├── flows/                    endpoints grouped into named steps (transactions)
    ├── payloads/                 request DTOs with default values
    └── tests/                    OrderLoadTest (thread groups of flows), PerformanceTest

src/test/resources/
└── example/uploads/scan.bin      file uploaded by the example
```

The layers, from bottom to top:

1. **Models** (generated): request and response classes from the spec, rebuilt on every build in
   `target/generated-test-sources/`.
2. **Payloads**: valid request objects with default values, e.g.
   `Payloads.createOrder().priority(true)`.
3. **Endpoints**: one method per API call, returning a new `httpSampler` with its body, headers,
   checks and extractors.
4. **Flows**: endpoints grouped into business steps with `transaction(...)`, each reported as its
   own line in the results.
5. **Tests**: thread groups of flows, plus assertions on the results.

## Getting started with your own API

1. Copy your spec to `openapi/api.yaml` and run `mvn clean test-compile`. Your schemas are now
   classes in package `api.model`. See [openapi/README.md](openapi/README.md) for other locations
   and known generator quirks.
2. Write a payload method per request body in `payloads/` (see `example/payloads/Payloads.java`).
3. Write an endpoint class per API area in `endpoints/` (see `example/endpoints/`). It shows a GET
   with query params, a JSON POST, a raw `application/octet-stream` file upload with an optional
   header, and an `application/x-www-form-urlencoded` login.
4. Group endpoints into flows in `flows/` (see `example/flows/UserFlows.java`).
5. Write a load test in `tests/` (see `example/tests/OrderLoadTest.java`) and run it against your
   environment:

   ```bash
   mvn clean test -DbaseUrl=https://my-environment -Dusername=... -Dpassword=...
   ```

Files to upload go anywhere under `src/test/resources`; pass their path relative to that folder.

## CSV data inside thread groups

`example/tests/OrderLoadTest` puts a separate `csvDataSet` inside each thread group.
The files live in `src/test/resources/example/data/`:

| Thread group | File | Variables used |
| --- | --- | --- |
| browsers | `browsers.csv` | `ITEM_ID` selects the item to browse |
| buyers | `buyers.csv` | Login, item ID, customer, quantities, standard and priority order templates |
| uploaders | `uploaders.csv` | Login, order template and data, upload resource, `MIME_CONTENT_ID` header |

For example, the browsers group is configured like this:

```java
threadGroup("browsers")
    .rampToAndHold(10, Duration.ofSeconds(10), Duration.ofSeconds(30))
    .children(
        csvDataSet(TestResources.path("example/data/browsers.csv")),
        UserFlows.browseItem(ref(ITEM_ID))
    )
```

The first CSV line contains variable names, not data. At the start of each
thread-group iteration, JMeter reads one data row and sets those variables for
that thread. `ref(ITEM_ID)` produces `${ITEM_ID}`, which JMeter replaces at request
execution time. All requests in that iteration can use the same row. The dataset
is scoped to its containing thread group, so the browsers' item data does not
feed the buyers or uploaders.

The example explicitly uses `.sharedIn(Sharing.THREAD_GROUP)`, so threads in each
group share a file cursor: one browser takes one row and the next
browser takes the next available row. Thread scheduling determines which thread
gets which row. At the end of the file, the dataset recycles from the first data
row, allowing these small example files to support more iterations than rows.
Each group has its own cursor even if multiple groups later use the same file.

Buyers and uploaders log in on every iteration using that iteration's CSV
credentials. This keeps the token aligned with the current row. `loginOnce()`
is still available, but would keep the first login's token even as CSV rows
advance. CSV credentials replace the `-Dusername` and `-Dpassword` defaults for
these groups. The sample credentials are placeholders, and repeated rows allow
the example to use a single test account. Replace them with accounts and item IDs
that exist in your API. Order bodies use the JSON templates selected by each row.

To add columns, add names to the header and values to each row, then pass
`ref("COLUMN_NAME")` into a sampler or flow. CSV headers are case-sensitive.
`TestResources.path(...)` resolves each file from the test classpath and fails
when building the plan if it is missing. CSV-selected templates and upload resources
are resolved at request execution time, when the current row is available.

### Multiple requests using one row

Java flows define the sequence; CSV files define inputs. The buyers flow logs in,
fetches an item, creates a standard order using `ORDER_TEMPLATE`, then creates a
priority order using `PRIORITY_ORDER_TEMPLATE`. All use the same row. The uploaders
flow logs in, creates an order, extracts `ORDER_ID`, and uploads `UPLOAD_FILE` to
that order. Extracted values stay local to each virtual user.

Templates live in `src/test/resources/example/requests/`. For example:

```json
{
  "customer_id": "${CUSTOMER_ID}",
  "lines": [{"item_id": "${ITEM_ID:number}", "quantity": "${QUANTITY:number}"}],
  "priority": false
}
```

`JsonTemplates.render(path, variables)` parses and caches the template, copies it,
and resolves placeholders in JSON string values on each request:

| Placeholder | Result |
| --- | --- |
| `${CUSTOMER_ID}` | Escaped JSON string from a CSV column or extracted variable |
| `${QUANTITY:number}` | JSON number |
| `${IS_PRIORITY:boolean}` | JSON boolean; accepts exactly `true` or `false` |
| `${now}` | Fresh UTC timestamp in ISO-8601 format |
| `${datumVandaag}` | Today's date in Europe/Amsterdam, formatted as `YYYY-MM-DD` |
| `${datumTijdVandaag}` | Midnight today in Europe/Amsterdam, e.g. `2026-10-26T00:00:00+01:00` |
| `${uuid}` | Fresh random UUID |
| `${randomString:12}` | 12 random ASCII letters (`A–Z`, `a–z`) |
| `${randomDigits:8}` | 8 random digits as a JSON string, preserving leading zeros |
| `${birth_date}` | Random date of birth for a completed age of 18–80 |
| `${birth_date:18:65}` | Random date of birth for a completed age of 18–65 (inclusive) |

Random string/digit lengths must be between 1 and 10000. Birth dates use `YYYY-MM-DD`,
with today calculated in UTC; ages must satisfy `0 <= minAge <= maxAge <= 150`.
Each placeholder generates a fresh value for each request. Values may repeat, and
two occurrences of a generator within one request are evaluated independently.
For example, a person request template could contain:

```json
{
  "first_name": "${randomString:10}",
  "reference": "${randomDigits:8}",
  "birth_date": "${birth_date:18:65}"
}
```

Placeholders must occupy the entire string value; object keys and parts of strings
are not interpolated. Nested objects and arrays are supported. Missing variables,
missing files and invalid typed values raise descriptive errors. Inserted values
are not recursively interpreted as template expressions. `now`, `datumVandaag`,
`datumTijdVandaag`, `uuid`, `randomString`,
`randomDigits`, and `birth_date` are reserved generator names.
Only add timestamp/UUID fields where your API accepts them; the example order API
does not define those fields. Existing DTO-based methods remain available.

To add another JSON request, add a template and its CSV path column, then add a
sampler to the Java flow using a runtime body supplier, as in
`OrderEndpoints.createOrderFromCsv("ORDER_TEMPLATE")`. Add the dataset once at the
thread-group level, outside nested loops, to retain one row per flow iteration.

Run the local template and multi-request flow tests without a running API:

```shell
mvn "-Dtest=JsonTemplatesTest,RandomTemplateValuesTest,PayloadsTest,CsvTemplateFlowTest" test
```

See the [DSL CSV guide](https://abstracta.github.io/jmeter-java-dsl/guide/#csv-as-input-data-for-requests)
for sharing and end-of-file settings. The API load test remains disabled until
you start the example API, configure its data, and remove `@Disabled`.

## Ramp-up and hold profiles

`OrderLoadTest` combines duration-based and iteration-based thread groups:

| Thread group | Ramp-up | Hold | Iterations |
| --- | --- | --- | --- |
| browsers | Reach 10 users over 10 seconds | 30 seconds at 10 users | Repeat flows for the profile duration |
| buyers | Reach 2 users over 5 seconds | 20 seconds at 2 users | Repeat flows for the profile duration |
| uploaders | No configured ramp-up | No timed hold | 1 user runs 5 iterations |

`rampToAndHold(users, rampDuration, holdDuration)` starts users gradually during
the ramp-up, then keeps the target number active during the hold. Users execute
their flows during both phases. For example, browsers have a configured
40-second profile: 10 seconds ramp-up plus 30 seconds hold. There is no gradual
ramp-down in these examples. Actual completion can take longer while in-flight
requests finish.

Thread groups run concurrently by default, so the buyers' 25-second profile
overlaps the browsers' 40-second profile. The test waits for all groups to finish,
including the uploaders. Change the user counts and `Duration` values in
`OrderLoadTest` to suit your environment.

Unlike `threadGroup("uploaders", 1, 5, ...)`, timed profiles do not specify a
fixed iteration count. Each user repeats the flows until its scheduled execution
ends, reading a new CSV row on every iteration. Fast responses produce more
iterations and consume more rows; the example CSV files recycle when exhausted.
The hold sets concurrent users, not a fixed requests-per-second rate. These
profiles also use timers below: browsers pause between requests, while buyers
have their combined request throughput capped.

See the [DSL ramps and holds guide](https://abstracta.github.io/jmeter-java-dsl/guide/#thread-ramps-and-holds)
for multi-stage profiles and ramp-down configuration. The load test still needs
a running API and removal of `@Disabled` before it can execute.

## Timers: user delays and throughput control

`OrderLoadTest` demonstrates two types of timers. The browsers use a random
think-time delay:

```java
threadGroup("browsers")
    .rampToAndHold(10, Duration.ofSeconds(10), Duration.ofSeconds(30))
    .children(
        csvDataSet(TestResources.path("example/data/browsers.csv")),
        uniformRandomTimer(Duration.ofSeconds(1), Duration.ofSeconds(3)),
        UserFlows.browseItem(ref(ITEM_ID))
    )
```

`uniformRandomTimer` waits for a random duration from 1 through 3 seconds before
each sampler in its scope. This represents a user spending time reading or
deciding between actions. Timer position within the group does not limit its
scope: it applies to all samplers in that group, including samplers nested in
transactions and controllers.

The buyers use a throughput timer:

```java
threadGroup("buyers")
    .rampToAndHold(2, Duration.ofSeconds(5), Duration.ofSeconds(20))
    .children(
        csvDataSet(TestResources.path("example/data/buyers.csv")),
        throughputTimer(30),
        UserFlows.login(ref("USERNAME"), ref("PASSWORD")),
        UserFlows.placeOrder(),
        UserFlows.placePriorityOrder()
    )
```

`throughputTimer(30)` limits the buyer group to approximately 30 samples per
minute. A sample is one sampler execution, not one complete business flow. The
login, item lookup, order creation and priority-order creation therefore all
count toward the same limit. Other thread groups are unaffected because the
timer is inside the buyers group.

The throughput timer adds waits to stay below the configured rate. It cannot
increase traffic when responses are slow or there are too few threads. Its first
invocation in each thread does not wait, so a short burst may occur when threads
start. For strict target-rate testing where the thread count should adapt, use
an `rpsThreadGroup` instead.

Concurrency and throughput serve different purposes: the ramp profile controls
how many users are active, while the throughput timer caps how quickly their
individual requests run. Avoid placing both timer types at test-plan level unless
you intentionally want them to affect every thread group.

See the [DSL timer guide](https://abstracta.github.io/jmeter-java-dsl/guide/#timers)
for constant delays, random delays, synchronization and other throughput
calculation modes.

## HTML and JTL reports

`OrderLoadTest` writes both raw results and a standalone HTML report:

```java
testPlan(
    // thread groups ...
    jtlWriter("target/jmeter-results.jtl"),
    htmlReporter("target/jmeter-reports")
).run();
```

After the load test finishes, the generated artifacts are available under
`target/`:

```text
target/
├── jmeter-results.jtl
└── jmeter-reports/
    └── <date-time> <unique-id>/
        └── index.html
```

Open the generated `index.html` in a browser to inspect response-time graphs,
throughput, error rates and sampler statistics. `htmlReporter` automatically
uses a unique timestamp-and-ID directory for each run, so later runs do not
overwrite earlier reports. Running `mvn clean` removes all reports because they
are build artifacts under `target/`.

The JTL file contains the raw sample results. Load it in the JMeter GUI or use it
with another analysis tool when the HTML summary is not detailed enough. This
example uses a fixed JTL filename and treats it as the current run's artifact.
Use `mvn clean` before a fresh run, and copy results you want to retain outside
`target/`.

`OrderLoadTest` is disabled by default because it requires the example API.
After starting the API and updating the example CSV data, remove `@Disabled` and
run only the load test:

```bash
mvn -Dtest=example.tests.OrderLoadTest test -DbaseUrl=http://localhost:8000
```

The HTML report is generated when test-plan execution completes. If the JVM is
forcibly stopped during a run, report generation may not finish.

See the [DSL HTML reporter guide](https://abstracta.github.io/jmeter-java-dsl/guide/#generate-html-reports-from-test-plan-execution)
and [JTL logging guide](https://abstracta.github.io/jmeter-java-dsl/guide/#log-requests-and-responses)
for reporter naming and detailed logging options.

## Removing the example

Delete `src/test/java/example`, `src/test/resources/example`, `openapi/example`, and in `pom.xml`
the `example-api-models` execution, the `normalize-example-openapi-spec-path` execution and the
`openapi.example.spec` property.

## Executing tests

`example/tests/OrderLoadTest` is `@Disabled` because it needs the example API running. Run all
other tests with:

```bash
mvn clean test
```
