# Publishing Acceleration

Pushes to the repository's default branch publish development GitHub prereleases and
`dev.vegui:acceleration-api:4.3.2-SNAPSHOT`. Tags `vX.Y.Z` publish stable Releases
and Maven versions. Tags must match the base version in `gradle.properties` and
point into the default branch history. Advance the development version after release.
Other branches and pull requests only build and test. Java 25 is required.

Configure GitHub Actions variables `R2_ENDPOINT` (the bucket's S3 API endpoint,
including jurisdiction if applicable) and `R2_BUCKET`; configure secrets
`R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` with read/write access only to that
bucket. The public domain is `https://repo.vegui.dev`, with Maven paths at its root.
No Sonatype account is required. Do not use public bucket URLs as upload endpoints.

Configure Cloudflare to bypass caching for Maven metadata, SNAPSHOT paths and 404
responses. Immutable stable files can be cached indefinitely. No bucket objects are
deleted by publishing. A single serialized job merges existing artifact metadata;
do not run other publishers against the same coordinates concurrently. Uploads are
not a multi-object transaction; metadata is published last. Stable content may never
be replaced with different bytes; failed publication requires investigation, not deletion.
GitHub concurrency can coalesce queued jobs; it is not a durable FIFO of every push.

```kotlin
repositories { maven("https://repo.vegui.dev") }
dependencies {
    compileOnly("dev.vegui:acceleration-api:4.3.2")
    annotationProcessor("dev.vegui:acceleration-api:4.3.2")
}
```

Use this API instead of also declaring Velocity API (both contain the original
Velocity classes). Never shade the API into plugins. Existing Velocity plugins
retain their original package and API identities. Acceleration extensions live in
`dev.vegui.acceleration.api`; inject `AccelerationProxyServer` to access read-only
discovery. Plugins using these extensions require Acceleration, not stock Velocity.
Discovery snapshots contain no endpoints or credentials and are not connection
authorization. Check `current()` before trusting last-known topology. Listener
callbacks run on discovery threads and must not block. Subscribe before reading
the initial snapshot, and close subscriptions when your plugin shuts down.

The public extensions expose discovery and read-only local ADCR registration metadata
through `commandRegistry()`, including subscriptions. Remote execution remains internal.
Original Velocity interfaces receive no new abstract
methods. This does not imply compatibility with every future Velocity version.

Publication is triggered by git pushes, not by locally running `build` or `publish`:
Gradle publishes to a local staging directory first. Workflows test the executable
and stage API sources/Javadoc before giving the publication job credentials.
