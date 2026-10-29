---
id: testing
title: Testing your tools
---

# Testing your tools

The module `jeap-spring-boot-mcp-starter-test` lets you test tools the way an AI client uses them: over a real HTTP
port, with a real MCP client and with really signed JWTs that the resource server validates (signature, expiry,
issuer, audience).

## Dependency

```xml
<dependency>
    <groupId>ch.admin.bit.jeap</groupId>
    <artifactId>jeap-spring-boot-mcp-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

## Test setup

The resource server resolves the JWKS URI at startup, so the port has to be known before the context starts.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(McpTestSupportConfiguration.class)
class MyToolsIT {

    static final int PORT = freePort();

    @DynamicPropertySource
    static void ports(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("jeap.security.oauth2.resourceserver.authorization-server.jwk-set-uri",
                () -> "http://localhost:" + PORT + "/mcp-test-jwks/jwks.json");
    }

    @Autowired
    McpTestTokens tokens;

    @Test
    void readerCanCallTool() {
        try (McpTestClient client = McpTestClient.connect("http://localhost:" + PORT, "/mcp", tokens.withRoles("READER"))) {
            assertThat(client.call("my_tool", Map.of("id", "1")).isError()).isFalse();
        }
    }
}
```

Configure the issuer as `http://localhost/auth` and exclude the permit-all chain of the jEAP security test starter,
otherwise authentication is not tested:

```yaml
spring:
  autoconfigure:
    exclude: ch.admin.bit.jeap.security.test.resource.configuration.PermitAllWebSecurityConfiguration
jeap:
  security:
    oauth2:
      resourceserver:
        authorization-server:
          issuer: http://localhost/auth
```

## Provided classes

| Class                          | Purpose                                                                                       |
|--------------------------------|-----------------------------------------------------------------------------------------------|
| `McpTestSupportConfiguration`  | Publishes a JWKS endpoint with the test key and provides `McpTestTokens`                      |
| `McpTestTokens`                | Valid tokens with roles, for a chosen subject (`forUser`, e.g. to check different users' calls never mix security contexts), plus expired, wrong audience, unknown issuer and unknown-key tokens |
| `McpTestClient`                | MCP Java SDK client with bearer token; `ToolResult` exposes `isError()`, `text()`, `errorCode()` |
