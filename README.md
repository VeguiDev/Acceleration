# Acceleration

Acceleration is a fork of [PaperMC Velocity](https://github.com/PaperMC/Velocity)
supporting hierarchical proxies through authenticated incoming Modern Forwarding.
It is not an official PaperMC distribution.

See [proxy backend configuration and verification](docs/proxy-backend.md).
The generated executable is `proxy/build/libs/acceleration-proxy-4.3.2-SNAPSHOT-all.jar`.
Java 25 is required. Existing `velocity.toml`, plugins and Java API packages are retained.

## Upstream attribution

The following project description and links refer to the original Velocity project.
Acceleration retains its GPLv3 license, the API's MIT license, and upstream copyright notices.

A Minecraft server proxy with unparalleled server support, scalability,
and flexibility.

Velocity is licensed under the GPLv3 license.

## Goals

* A codebase that is easy to dive into and consistently follows best practices
  for Java projects as much as reasonably possible.
* High performance: handle thousands of players on one proxy.
* A new, refreshing API built from the ground up to be flexible and powerful
  whilst avoiding design mistakes and suboptimal designs from other proxies.
* First-class support for Paper, Sponge, Fabric and Forge. (Other implementations
  may work, but we make every endeavor to support these server implementations
  specifically.)
  
## Building

Velocity is built with [Gradle](https://gradle.org). We recommend using the
wrapper script (`./gradlew`) as our CI builds using it.

It is sufficient to run `./gradlew build` to run the full build cycle.

## Running

Once you've built Velocity, you can copy and run the `-all` JAR from
`proxy/build/libs`. Velocity will generate a default configuration file
and you can configure it from there.

Alternatively, you can get the proxy JAR from the [downloads](https://papermc.io/downloads/velocity)
page.

# Localisation

Translations are handled using [Crowdin](https://papermc-io.crowdin.com/velocity).
If you want to translate a language not available on Crowdin,
you might want to ask in the [Discord](https://discord.gg/papermc) about it.

[![CI powered by namespace badge](https://papermc.io/assets/misc/namespace-oss-badge.svg?project=velocity)](https://namespace.so/github-actions/?utm_source=oss&utm_campaign=papermc)
