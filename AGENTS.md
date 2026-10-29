# AGENTS.md

Guidance for AI coding agents working **in this repository**. For how to *use* the starter in a
consuming service, read [README.md](README.md) and the [docs/](docs/) folder instead.

## Project

jEAP Spring Boot MCP Starter is a Maven library that lets a jEAP service expose tools via the Model Context
Protocol. It builds on Spring AI MCP (WebMVC, `SYNC`/`STATELESS`, `@McpTool`/`@McpToolParam`) and adds jEAP
security integration, protection limits, error handling and observability. Domain tools and data authorization
stay in the consuming service.

## Repository layout

```text
pom.xml                                            # aggregator: jeap-spring-boot-mcp-starter-parent
jeap-spring-boot-mcp-starter-test/                 # reusable test support (McpTestClient, McpTestTokens, JWKS endpoint)
jeap-spring-boot-mcp-starter/src/main/java/ch/admin/bit/jeap/mcp/
  McpErrorCode, McpToolException                   # error contract for tool calls
  McpInvocation, McpInvocationObserver             # optional call observer extension point
  config/McpProperties                             # jeap.mcp.* properties
  security/McpSecurityAutoConfiguration            # dedicated /mcp filter chain, protected resource metadata
  security/McpToolAuthorizationValidator           # startup check: every published @McpTool/@Tool needs an
                                                    # enforceable @PreAuthorize (rejects final/non-public methods too)
  execution/McpExecutionAutoConfiguration          # wiring of the execution features
  execution/McpToolInvoker                         # deadline, concurrency, result size, errors, metrics, logs
  execution/McpToolErrorInterceptor                # AOP advice classifying exceptions of @McpTool/@Tool methods
  execution/McpToolSpecificationPostProcessor      # routes all tool specifications through the invoker
  execution/McpRequestSizeFilter                   # request body limit
jeap-spring-boot-mcp-starter/src/test/java/.../it/  # ITs: real HTTP port, real signed JWTs, real MCP client
Jenkinsfile, publiccode.yml, CHANGELOG.md, LICENSE
```

The Spring AI callback for `@McpTool`/`@Tool` methods swallows exceptions and returns their text, so exceptions
are classified in an AOP advice outside of method security and linked to the running call via `McpInvocationScope`.
Integration tests must exclude the permit-all chain of `jeap-spring-boot-security-starter-test`
(`spring.autoconfigure.exclude`), otherwise the security behaviour is not tested. Don't import the jEAP test JWKS
mock: its `/.well-known/**` matcher shadows the protected resource metadata endpoint. Use `McpTestSupportConfiguration`.

## Build & test

```bash
./mvnw verify        # full build incl. license checks
./mvnw -q install    # install the artifact locally
```

- Parent: `ch.admin.bit.jeap:jeap-internal-spring-boot-parent` (Spring Boot 4 aligned), JDK 25.
- Spring AI is imported via its BOM (`spring-ai.version`); jEAP starter versions via `jeap-starter.version`.
- Without a commit the git-commit-id plugin fails; use `-Dmaven.gitcommitid.skip=true` in a fresh clone.

## jEAP conventions

- Java packages live under `ch.admin.bit.jeap.mcp...`.
- Configuration properties use the prefix `jeap.mcp.*`; JWT settings stay under `jeap.security.oauth2.resourceserver.*`.
- Auto-configuration is registered via `@AutoConfiguration` and
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- No hard dependency on Kafka, jeap-messaging or the audit starter.

## Docs

When changing public behaviour, update the matching focused file under [docs/](docs/) (one topic per
file) and the documentation index in the README.

Validate that files under `docs/*.md` are valid Markdown compatible with a Docusaurus site. Also
validate that Mermaid diagrams in the Markdown files use correct Mermaid syntax.

## Versioning

- Semantic Versioning; all changes documented in [CHANGELOG.md](./CHANGELOG.md) (Keep a Changelog format).
- `setPomVersions.sh <version>` updates the version in the POM.
- When working on a feature branch, increase the version to `x.y.z-SNAPSHOT` in the POM.
- Always keep the -SNAPSHOT postfix in the POM, CI removes it when releasing a version. Do not use the
  SNAPSHOT postfix in other places (CHANGELOG, publiccode.yml etc).
- Keep changelog entries concise and follow existing patterns.
- Keep commit messages short, use the JIRA ID from the branch name as a prefix, do not use
  conventional commits (for example: "JEAP-1234 Added feature X").
- When bumping the version, also update the changelog and the version/date in `publiccode.yml`.
