---
id: getting-started
title: Getting started
---

# Getting started

The jEAP MCP starter lets a jEAP service expose tools via the
[Model Context Protocol](https://modelcontextprotocol.io) (MCP). It uses the Spring AI MCP server
(WebMVC, `SYNC`, `STATELESS`, Streamable HTTP) and adds jEAP security, execution limits, a stable error
contract and observability.

## 1. Add the dependency

```xml
<dependency>
    <groupId>ch.admin.bit.jeap</groupId>
    <artifactId>jeap-spring-boot-mcp-starter</artifactId>
</dependency>
```

The service must also be configured as a jEAP OAuth2 resource server
(`jeap.security.oauth2.resourceserver.authorization-server.issuer`, see the jEAP security starter).

## 2. Configure the MCP server

```yaml
spring:
  ai:
    mcp:
      server:
        name: my-service
        type: SYNC
        protocol: STATELESS
        stateless:
          mcp-endpoint: /mcp
        annotation-scanner:
          enabled: true

jeap:
  mcp:
    security:
      resource-uri: https://my-service.example.ch/mcp
```

`jeap.mcp.security.resource-uri` is required: it is published as the resource in the OAuth protected
resource metadata and is never derived from request headers.

## 3. Implement a tool

Every exported tool needs an explicit `@PreAuthorize` rule, on the method or on its class. The application
does not start otherwise (see [Security](security.md)).

```java
@Component
@RequiredArgsConstructor
public class ArchitectureTools {

    private final ArchitectureQueries queries;

    @McpTool(name = "get_system", description = "Returns architecture information about a system.")
    @PreAuthorize("hasRole('architecture', 'read')")
    public SystemDetails getSystem(
            @McpToolParam(description = "Name of the system", required = true) String systemName) {
        return queries.getSystem(systemName);
    }
}
```

The function authorization on the tool is only the entry check. The data authorization (may this caller read
this system?) stays in the service layer and must be applied before the data is returned.

## Further reading

| Topic                                      | File                                       |
|--------------------------------------------|--------------------------------------------|
| Security (authentication, default deny)    | [security.md](security.md)                 |
| Execution limits, errors and observability | [execution.md](execution.md)               |
| Configuration reference (`jeap.mcp.*`)     | [configuration.md](configuration.md)       |
