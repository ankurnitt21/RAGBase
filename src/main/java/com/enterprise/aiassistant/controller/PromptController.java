package com.enterprise.aiassistant.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.enterprise.aiassistant.entity.PromptTemplate;
import com.enterprise.aiassistant.repository.PromptTemplateRepository;
import com.enterprise.aiassistant.service.PromptService;

@RestController
@RequestMapping("/api/v1/prompts")
public class PromptController {

    private final PromptService promptService;
    private final PromptTemplateRepository repository;

    public PromptController(PromptService promptService, PromptTemplateRepository repository) {
        this.promptService = promptService;
        this.repository = repository;
    }

    /** List all versions of a prompt by name. */
    @GetMapping("/{name}")
    public List<PromptTemplate> getVersions(@PathVariable String name) {
        return repository.findByNameOrderByVersionDesc(name);
    }

    /** Get the currently active prompts and their versions. */
    @GetMapping("/active")
    public Map<String, Integer> getActiveVersions() {
        return promptService.getActiveVersions();
    }

    /** Create a new version of a prompt (inactive by default). */
    @PostMapping("/{name}")
    public PromptTemplate createVersion(@PathVariable String name,
                                         @RequestBody CreatePromptRequest request) {
        return promptService.createVersion(name, request.content(), request.description());
    }

    /** Activate a specific version of a prompt. */
    @PutMapping("/{name}/activate/{version}")
    public ResponseEntity<Void> activate(@PathVariable String name, @PathVariable int version) {
        promptService.activateVersion(name, version);
        return ResponseEntity.ok().build();
    }

    /** Hot-reload prompts from DB without restart. */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refresh() {
        promptService.refresh();
        return ResponseEntity.ok(Map.of("status", "refreshed", "active", promptService.getActiveVersions().toString()));
    }

    record CreatePromptRequest(String content, String description) {}
}
