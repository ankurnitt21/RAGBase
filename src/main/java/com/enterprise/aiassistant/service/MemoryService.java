package com.enterprise.aiassistant.service;

import com.enterprise.aiassistant.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private final Path csvPath;

    public MemoryService(@Value("${app.memory.csv-path:data/chat_messages.csv}") String csvPath) {
        this.csvPath = Paths.get(csvPath);
        initCsv();
    }

    private void initCsv() {
        try {
            Files.createDirectories(csvPath.getParent());
            if (!Files.exists(csvPath)) {
                Files.writeString(csvPath, "conversation_id,role,content,created_at\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize CSV file: " + csvPath, e);
        }
    }

    /**
     * Returns the last 10 messages for a conversation in chronological order.
     */
    public List<ChatMessage> getRecentMessages(String conversationId) {
        List<ChatMessage> matched = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(csvPath);
            for (int i = 1; i < lines.size(); i++) { // skip header
                String[] parts = parseCsvLine(lines.get(i));
                if (parts.length >= 4 && parts[0].equals(conversationId)) {
                    matched.add(new ChatMessage(parts[0], parts[1], parts[2],
                            LocalDateTime.parse(parts[3])));
                }
            }
        } catch (IOException e) {
            log.error("Failed to read chat history CSV", e);
        }
        // Return last 10
        int from = Math.max(0, matched.size() - 10);
        return new ArrayList<>(matched.subList(from, matched.size()));
    }

    public void saveMessage(String conversationId, String role, String content) {
        String line = toCsvLine(conversationId, role, content, LocalDateTime.now().toString());
        try {
            Files.writeString(csvPath, line + "\n", StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write to chat history CSV", e);
        }
    }

    private String toCsvLine(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(",");
            // Escape: wrap in quotes, double any internal quotes
            sb.append("\"").append(fields[i].replace("\"", "\"\"")).append("\"");
        }
        return sb.toString();
    }

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(current.toString());
                    current = new StringBuilder();
                } else {
                    current.append(c);
                }
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }
}
