package com.enterprise.aiassistant.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.api.common.AttributeKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

@Configuration
public class TracingConfig {

    private static final Logger log = LoggerFactory.getLogger(TracingConfig.class);

    @Value("${langsmith.api-key:}")
    private String langSmithApiKey;

    @Value("${langsmith.endpoint:https://api.smith.langchain.com/otel/v1/traces}")
    private String langSmithEndpoint;

    @Value("${langsmith.project:rag-system}")
    private String langSmithProject;

    private SdkTracerProvider tracerProvider;

    @Bean
    public OpenTelemetry openTelemetry() {
        Resource resource = Resource.getDefault().merge(
                Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "rag-system",
                        AttributeKey.stringKey("project.name"), langSmithProject
                ))
        );

        if (langSmithApiKey == null || langSmithApiKey.isBlank()) {
            log.warn("LangSmith API key not configured — tracing will use no-op exporter");
            tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .build();
        } else {
            OtlpHttpSpanExporter spanExporter = OtlpHttpSpanExporter.builder()
                    .setEndpoint(langSmithEndpoint)
                    .addHeader("x-api-key", langSmithApiKey)
                    .addHeader("Langsmith-Project", langSmithProject)
                    .build();

            tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
                    .build();

            log.info("LangSmith OTLP tracing configured → project='{}', endpoint='{}'",
                    langSmithProject, langSmithEndpoint);
        }

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("rag-system", "1.0.0");
    }

    @PreDestroy
    void shutdown() {
        if (tracerProvider != null) {
            tracerProvider.close();
        }
    }
}
