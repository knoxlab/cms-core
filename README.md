# PowerCMS 12 · Core

PowerCMS Community Edition core: a Spring Boot / Spring Modulith modular monolith covering identity,
sites, content models, content, media, workflow, publishing, search, site packages, audit and delivery.

This repository contains **Community Edition code only**. The following capabilities are intentionally
absent and live in separate, proprietary modules:

- Knox-Media integration, transcoding, HLS playback and DRM
- Interaction operations, custom forms and submission review
- AI / RAG knowledge base, hybrid search, MCP agent tooling
- External data sources, object-level ACL and offline licensing
- Enterprise clustering, SSO and high-risk dual approval

## Requirements

- JDK 21
- Maven 3.9+
- Optional: the `admin-ui` build output at `../admin-ui/dist` to serve the management SPA

## Build and test

```bash
mvn clean test
mvn clean package
```

Run locally:

```bash
mvn spring-boot:run
```

Media defaults to local filesystem storage under `powercms.media.storage-root`.

## License

Apache License 2.0. See [LICENSE](LICENSE).
