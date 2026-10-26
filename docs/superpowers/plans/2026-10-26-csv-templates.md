# CSV request templates

Goal: one CSV per thread group, one row per iteration, reused by all requests in that flow.

Design agreed in chat: Java defines the request sequence; CSV selects JSON templates and supplies
scenario values; response variables remain local to the thread. Parse JSON before substituting
whole-value placeholders, preserving escaping and explicit numeric/boolean types. Cache templates
and copy them for every rendering. Generate timestamps and UUIDs at request execution time.
Keep existing DTO-based endpoint overloads. Do not add fields absent from the example API contract.

Implementation and verification:
- [x] Add failing template tests for escaping, types, missing values, fresh dynamic values and isolation.
- [x] Implement common.JsonTemplates with classpath caching and strict whole-value placeholders.
- [x] Connect runtime template bodies to order endpoints and CSV-based multi-request flows.
- [x] Update buyers/uploaders CSVs and README; explicitly share datasets within each thread group.
- [x] Run helper tests and a local HTTP integration test covering multiple iterations and requests.

Constraints: Java 11, existing Jackson/JMeter dependencies, no live API required for verification.
Review focus: missing variables, invalid typed values, template mutation across threads, CSV row
advancement, and correlation of response IDs with later requests.

Verification: `mvn "-Dtest=JsonTemplatesTest,PayloadsTest,CsvTemplateFlowTest" test` passed
(6 tests, zero failures). `git diff --check` passed. Local HTTP verification covers
two concurrent buyer threads and two uploader iterations, including raw file sizes
and response-ID correlation. Template tests were first run before implementation
and failed because JsonTemplates did not exist. Changes remain uncommitted for review.
