---
id: compatibility
title: Compatibility profile
---

# Compatibility profile

This is the one combination of versions the starter is actually built and tested against. The values below are
not independent choices: moving one usually means re-checking the others.

## Tested combination

The exact pinned versions live in one place, the root `pom.xml`, and are not repeated here -- a number copied
into this doc would silently go stale the next time a dependency bumps. To see what's actually pinned right
now, either open `pom.xml` directly or ask Maven:

```bash
./mvnw help:evaluate -Dexpression=spring-ai.version -DforceStdout
./mvnw help:evaluate -Dexpression=jeap-starter.version -DforceStdout
./mvnw help:evaluate -Dexpression=spring-boot.version -DforceStdout
```

| Component                          | Pinned via                                              |
|-------------------------------------|-----------------------------------------------------------|
| JDK                                 | `maven.compiler.release`, via the jEAP parent             |
| Spring Boot                        | the jEAP parent (`jeap-internal-spring-boot-parent`)       |
| Spring AI (and the MCP Java SDK, MCP WebMVC integration -- both transitive via Spring AI) | `spring-ai.version` property, imported as a BOM |
| `jeap-spring-boot-security-starter` (and its `-test` module) | `jeap-starter.version` property          |

MCP wire protocol: whichever version the MCP Java SDK on the classpath negotiates with a client (visible in a
client's `initialize` request/response in the server log). This is not configurable, and not a `jeap.mcp.*`
property -- a future SDK upgrade only changes it once that upgrade has actually happened, so there is nothing
to pin or document separately here either.

## Why the stable release

Spring AI also ships release candidates ahead of its stable releases, which can require a newer Spring Boot
version than jEAP's parent currently pins -- a mismatch that would have to be resolved before such a version
could be used here. Staying on Spring AI's stable release line avoids that: it pulls in the Spring Boot version
the jEAP parent already pins, with no pre-release dependency in the mix.

## Upgrade path

1. Bump `spring-ai.version` and/or the jEAP parent version in the root `pom.xml`.
2. Run `./mvnw verify` (`-Dmaven.gitcommitid.skip=true` outside CI). The integration test suite exercises the
   real registration and security path end to end; a version bump that breaks it fails loudly here, not later.
3. Before taking a pre-release of Spring AI, check which Spring Boot version it actually requires and
   cross-check it against the jEAP parent's current pin first -- see "Why the stable release" above.
4. If the new MCP SDK version negotiates a different protocol version, re-run the real-client checks in
   [`jme-mcp-example`](https://github.com/jeap-admin-ch/jme-mcp-example) (see its README's "Verified with real
   clients" section) rather than assuming compatibility from the SDK changelog alone.

## Client compatibility

Client-side compatibility (Copilot, Codex CLI, Claude Code) is verified separately, against a running example
service rather than in this repository's own test suite: see
[`jme-mcp-example`](https://github.com/jeap-admin-ch/jme-mcp-example)'s README for the exact client versions
tested, the setup steps per client, and what each one was actually able to do (connect, log in, discover tools,
call them).
