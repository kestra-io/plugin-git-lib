# Mock Kestra API server test guide

`MockKestraApiServer` is a test fixture backed by an in-memory flow repository.
Start it with `MockKestraApiServer.start(flowRepository)`, pass `url()` to the
client under test, and close it with try-with-resources. The fixture covers
flow export, import, validation, deletion, lookup, and namespace lookup; use
`forceGetFlowStatus` to exercise non-404 API failures without a live Kestra
instance.

Tests using the fixture belong in the consuming plugin's integration test
source set. Run the library's fixture compilation and test task with:

```bash
./gradlew test
```
