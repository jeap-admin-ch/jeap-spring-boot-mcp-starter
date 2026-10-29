# jEAP Spring Boot MCP Starter

Spring Boot starter for exposing the tools of a jEAP service via the Model Context Protocol (MCP). It builds on
Spring AI (WebMVC, `SYNC`/`STATELESS`, `@McpTool`/`@McpToolParam`) and adds what each service would otherwise have to
implement itself:

* Authentication and function authorization integrated with the jEAP security starter
* OAuth discovery (protected resource metadata)
* Execution protection (timeouts, concurrency, request and result size limits)
* Stable error contract and observability

The domain tools and data authorization stay in the consuming service.

## Documentation

Start with [Getting started](docs/getting-started.md), then follow the links below.

| Topic                                                         | File                                           |
|---------------------------------------------------------------|------------------------------------------------|
| Getting started (dependency, configuration, first tool)       | [docs/getting-started.md](docs/getting-started.md) |
| Security (authentication, OAuth discovery, default deny)      | [docs/security.md](docs/security.md)           |
| Execution limits, error contract, observability, rate limits  | [docs/execution.md](docs/execution.md)         |
| Configuration reference (`jeap.mcp.*`)                        | [docs/configuration.md](docs/configuration.md) |
| Testing your tools (`jeap-spring-boot-mcp-starter-test`)      | [docs/testing.md](docs/testing.md)             |
| Compatibility profile (tested versions, upgrade path)          | [docs/compatibility.md](docs/compatibility.md) |

## Changes

This library is versioned using [Semantic Versioning](http://semver.org/) and all changes are documented in
[CHANGELOG.md](./CHANGELOG.md) following the format defined in [Keep a Changelog](http://keepachangelog.com/).

## Note

This repository is part the open source distribution of jEAP. See [github.com/jeap-admin-ch/jeap](https://github.com/jeap-admin-ch/jeap)
for more information.

## License

This repository is Open Source Software licensed under the [Apache License 2.0](./LICENSE).
