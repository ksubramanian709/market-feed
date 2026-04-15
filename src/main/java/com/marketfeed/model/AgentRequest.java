package com.marketfeed.model;

import lombok.Data;

import java.util.List;

@Data
public class AgentRequest {
    /** Natural language question */
    private String question;

    /** Optional pinned symbols; agent also auto-detects from question text */
    private List<String> symbols;

    /** Prior turns in this conversation, oldest first */
    private List<Turn> history;

    @Data
    public static class Turn {
        private String role;    // "user" | "assistant"
        private String content;
    }
}
