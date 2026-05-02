package com.enterprise.aiassistant.dto;

/**
 * A single suggested clarification option returned when the system detects
 * an ambiguous question. Mirrors the structure produced by AmbiguityLlmChecker.
 *
 * index   — 1-based display number shown to the user.
 * query   — the refined, self-contained question the user could choose.
 * reason  — one-sentence explanation of why this interpretation was suggested.
 */
public record ClarifyOption(int index, String query, String reason) {}
