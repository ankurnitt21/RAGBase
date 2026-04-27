package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.dto.Domain;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IngestionJobStore {

    public enum State { PENDING, COMPLETED, FAILED }

    public record JobStatus(String jobId, String filename, Domain domain, State state, String message) {}

    private final ConcurrentHashMap<String, JobStatus> jobs = new ConcurrentHashMap<>();

    public void register(String jobId, String filename, Domain domain) {
        jobs.put(jobId, new JobStatus(jobId, filename, domain, State.PENDING, "Queued for ingestion"));
    }

    public void complete(String jobId) {
        jobs.computeIfPresent(jobId, (k, v) ->
                new JobStatus(k, v.filename(), v.domain(), State.COMPLETED, "Ingested successfully"));
    }

    public void fail(String jobId, String error) {
        jobs.computeIfPresent(jobId, (k, v) ->
                new JobStatus(k, v.filename(), v.domain(), State.FAILED, error));
    }

    public Optional<JobStatus> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
