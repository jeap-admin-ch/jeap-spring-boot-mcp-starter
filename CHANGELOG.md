# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.8.0] - 2026-11-25

### Changed
- Update parent from 11.17.0 to 12.0.0
- update jeap-starter from 25.34.0 to 25.35.0
- Application starter: adapt the reverse proxy defaults to Spring Boot 4.2. The forwarded header strategy now
  defaults to `server.request.header.forwarded.strategy=web-server` (replacing the deprecated
  `server.forward-headers-strategy=NATIVE`), and only if the application sets neither of the two properties itself,
  so an application's own setting of either property is respected (Spring Boot refuses to start if both are set).
  The `server.tomcat.use-relative-redirects=true` default was dropped, as Spring Boot defaults to relative redirects.

## [1.7.0] - 2026-11-21

### Changed
- Update parent from 11.16.0 to 11.17.0
- update jeap-starter from 25.33.0 to 25.34.0

## [1.6.0] - 2026-11-19

### Changed
- Update parent from 11.15.0 to 11.16.0
- update jeap-starter from 25.32.0 to 25.33.0

## [1.5.0] - 2026-11-17

### Changed
- Update parent from 11.14.0 to 11.15.0
- update jeap-starter from 25.31.0 to 25.32.0

## [1.4.0] - 2026-11-14

### Changed
- Update parent from 11.13.0 to 11.14.0
- update jeap-starter from 25.30.0 to 25.31.0

## [1.3.0] - 2026-11-10

### Changed
- Update parent from 11.12.0 to 11.13.0
- update jeap-starter from 25.29.0 to 25.30.0

## [1.2.0] - 2026-11-09

### Changed
- Update parent from 11.11.0 to 11.12.0
- update jeap-starter from 25.28.0 to 25.29.0

## [1.1.0] - 2026-11-03

### Changed
- Update parent from 11.10.0 to 11.11.0
- update jeap-starter from 25.27.0 to 25.28.0

## [1.0.0] - 2026-11-03

### Added

- Initial starter setup
