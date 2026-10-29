---
id: configuration
title: Configuration reference
---

# Configuration reference

Transport and server metadata are configured with the Spring AI properties (`spring.ai.mcp.server.*`).
JWT issuer and audience are configured with `jeap.security.oauth2.resourceserver.*`. The starter only adds the
following technical properties; the values shown are the defaults. They are not validated production values.

| Property                                         | Default | Description                                                     |
|--------------------------------------------------|---------|-----------------------------------------------------------------|
| `jeap.mcp.security.resource-uri`                 | -       | **Required.** External URL of the MCP endpoint.                 |
| `jeap.mcp.security.anonymous-access`             | `false` | Allows requests without a bearer token, see [Security](security.md#anonymous-access). |
| `jeap.mcp.execution.timeout`                     | `15s`   | Default execution deadline of a tool call.                      |
| `jeap.mcp.execution.observer-timeout`            | `1s`    | Deadline for a single `McpInvocationObserver` notification, see [Execution](execution.md#metrics-logs-and-observers). |
| `jeap.mcp.limits.max-request-size`               | `256KB` | Maximum request body size.                                      |
| `jeap.mcp.limits.max-response-size`              | `1MB`   | Maximum serialized result size.                                 |
| `jeap.mcp.limits.max-concurrent-calls`           | `20`    | Concurrent tool calls per instance.                             |
| `jeap.mcp.tools.<name>.timeout`                  | -       | Deadline for this tool, replaces the default.                   |
| `jeap.mcp.tools.<name>.max-response-size`        | -       | Result size limit for this tool, replaces the default.          |
| `jeap.mcp.tools.<name>.max-concurrent-calls`     | -       | Concurrency limit for this tool, in addition to the global one. |

```yaml
jeap:
  mcp:
    security:
      resource-uri: https://my-service.example.ch/mcp
      anonymous-access: false
    execution:
      timeout: 15s
    limits:
      max-request-size: 256KB
      max-response-size: 1MB
      max-concurrent-calls: 20
    tools:
      get_system:
        timeout: 5s
        max-response-size: 256KB
        max-concurrent-calls: 10
```

- `<name>` is the published tool name. A name that does not match a tool fails the startup.
- The global and the per-tool concurrency limits apply at the same time.
- Permissions are not configurable: authorization is declared with `@PreAuthorize` on the tools.
  `anonymous-access` only decides whether a missing token reaches the endpoint at all; each tool still enforces
  its own rule.

## Rate limiting

The starter does not rate limit; see [Rate limiting](execution.md#rate-limiting) for the decision and its limits.
