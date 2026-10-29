---
id: security
title: Security
---

# Security

## Authentication

The MCP endpoint is protected by a dedicated, stateless security filter chain. If a jEAP OAuth2 resource server
is configured (the usual case), it reuses the JWT validation and the authentication token of the jEAP security
starter, so issuer, signature, expiry and audience are checked as for every other jEAP resource. In contrast to
the default jEAP chain, CSRF protection is disabled for the endpoint. MCP clients authenticate with a bearer
token only and rely on a `401` response with a `WWW-Authenticate` challenge to start the OAuth flow; a `403`
from the CSRF filter would break that.

### Anonymous access

By default every request to the endpoint must be authenticated. Setting `jeap.mcp.security.anonymous-access: true`
is a deliberate opt-in that permits requests without a bearer token to reach the endpoint. A token that is present
but invalid or expired is still rejected with `401`, only the absence of a token is tolerated.

This does not open up any tool: default deny still applies (see below), so every tool keeps denying anonymous
callers unless it declares `@PreAuthorize("permitAll()")` itself, in which case it returns its result to an
anonymous caller; any other tool returns `FORBIDDEN`. Spring AI exposes a single MCP endpoint per application, so
this means public and protected tools on the same endpoint, not a separate anonymous one. The protected resource
metadata stays published either way.

### Running with no resource server at all

`anonymous-access` also covers running a service that is not a jEAP OAuth2 resource server at all, i.e. no
`jeap.security.oauth2.resourceserver.*` is configured. There is then no decoder to ever validate a bearer token
with, so every caller is permanently anonymous:

- `jeap.mcp.security.anonymous-access: true` is mandatory in that case; the startup fails otherwise, since the
  endpoint could then never be reached by anyone.
- Default deny still applies the same way: a tool needs an explicit `@PreAuthorize("permitAll()")` to be
  reachable. A tool that requires a role is not wrong, just permanently unreachable, since there is no way to
  ever be anything but anonymous.
- The protected resource metadata is not published, since there is no authorization server to describe.
- `@PreAuthorize` is still enforced: the starter enables method security itself in this case, since the jEAP
  security starter only does so once its resource server is configured.

## OAuth discovery

The protected resource metadata is served without authentication at `/.well-known/oauth-protected-resource`
and contains the configured `jeap.mcp.security.resource-uri` and the issuers of the configured authorization
servers. A `401` response points to it:

```text
WWW-Authenticate: Bearer resource_metadata="https://my-service.example.ch/.well-known/oauth-protected-resource"
```

The MCP client uses this to find out where to obtain a token. Signing in and issuing tokens remains the job of
the authorization server.

## Default deny for tools

A security annotation on one method does not protect a tool that has none. The starter therefore fails the
application startup if an `@McpTool` or Spring AI `@Tool` method has no effective `@PreAuthorize` rule, neither on
the method nor on its class (meta-annotations are supported):

```text
MCP tools without an explicit authorization rule are not allowed. Add @PreAuthorize to the tool method or its
class: tool 'unprotected' (com.example.Tools#unprotected)
```

There is no property to switch this off. Note that a method rule replaces a class rule, it is not combined with it.

The startup also fails if tools exist but method security is not enabled, because `@PreAuthorize` would then be
ignored without any error. The jEAP security starter enables `@EnableMethodSecurity` for you once its resource
server is configured; without one, the MCP starter enables it itself (see
[Running with no resource server at all](#running-with-no-resource-server-at-all)).

A tool published through neither annotation, for example a hand-built `ToolCallback` or `SyncToolSpecification`
bean, cannot be checked for a `@PreAuthorize` rule at all, since there is no annotated method to inspect. The
startup fails for these too:

```text
MCP tools [handBuilt] are published but not backed by an @McpTool or @Tool method, so their authorization cannot be
verified. Expose them through such a method with an explicit @PreAuthorize rule instead.
```

Define such tools through an `@McpTool` or `@Tool` method instead, so the check above applies to them.

## Data authorization

The starter provides the authenticated context. The decision which data a caller may read, and applying it to
queries, lists, counts and aggregations, is the responsibility of the service. Do not treat a system name or an id
passed by the agent as proof of authorization.

`tools/list` is only protected by authentication when `jeap.mcp.security.anonymous-access` is `false` (the
default). With anonymous access enabled, tool names and descriptions are visible to unauthenticated callers too
-- individual tool calls still default-deny on their own, see above. Either way, tool descriptions must not
contain confidential information.
