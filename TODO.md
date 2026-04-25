# TODO

## Critical

- [ ] Add CORS configuration (`CorsConfigurer` bean) so browser-based frontends are not blocked
- [ ] Fix async error surfacing in `IngestionService.ingestAsync()` -- exceptions are currently swallowed and returned as a string; caller never knows about failures
- [ ] Add rate limiting to all endpoints (Bucket4j or Resilience4j) -- OpenAI calls cost money and are unbounded
- [ ] Add retry + circuit breaker on LLM and Pinecone calls (`@Retry`, `@CircuitBreaker` via Resilience4j)
- [ ] Enforce file MIME type at the controller level -- currently only content is checked, not the upload type

## Improvements

- [ ] Externalize LLM parameters (`modelName`, `temperature`, `maxTokens`) from `AiConfig` to `application.yml` so they can be tuned without redeploying
- [ ] Validate `conversationId` as UUID format before it reaches the DB query
- [ ] Add an async ingestion status endpoint so callers can check whether a bulk upload succeeded
- [ ] Add custom `HealthIndicator` beans for OpenAI and Pinecone so `/actuator/health` reflects real dependency status
- [ ] Make domain routing failure visible -- currently silently falls back to `PRODUCT` with only a WARN log

## Security

- [ ] Per-user API keys with rotation -- current single shared key means one compromise affects everything
- [ ] Sanitize `conversationId` and query inputs against prompt injection in `KnowledgeBaseTools`

## Testing

- [ ] Integration tests for `/api/v1/chat` and `/api/v1/documents` endpoints
- [ ] Service-level unit tests for `ChatService`, `IngestionService`, `DomainRouterService` with mocked dependencies
- [ ] Security filter tests for `ApiKeyAuthFilter` (missing key, wrong key, excluded paths)
- [ ] Exception handler tests for all cases in `GlobalExceptionHandler`
