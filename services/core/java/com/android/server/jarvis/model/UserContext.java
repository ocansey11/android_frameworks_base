package com.android.server.jarvis.model;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;

/**
 * The brain — persistent facts JarvisOS learns about the user.
 * Single record per device, updated over time.
 */
@Entity
public class UserContext {
    @Id
    public long id;

    public String preferredName;
    public String interests;        // comma-separated topics user frequently asks about
    public String frequentFolders;  // comma-separated folder paths accessed most
    public long lastActiveAt;
    public String deviceLocale;

    /**
     * JSON array of learned facts about the user.
     * Written by DreamWorker (Phase 6). Read by PlanNode and RespondNode to personalise responses.
     * Example: ["User is a software engineer", "User frequently works with Python files",
     *           "User prefers concise answers"]
     * DreamWorker merges new facts and removes contradictions before writing.
     */
    public String facts;

    /** Epoch ms when DreamWorker last ran consolidation. 0 = never run. */
    public long consolidatedAt;

    public UserContext() {}

    public UserContext(String deviceLocale) {
        this.deviceLocale = deviceLocale;
        this.lastActiveAt = System.currentTimeMillis();
    }
}
