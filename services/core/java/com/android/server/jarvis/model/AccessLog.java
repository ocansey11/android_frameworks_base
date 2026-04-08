package com.android.server.jarvis.model;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;

/**
 * Lightweight log of file/folder access per conversation.
 * Used to improve retrieval ranking over time — no ML, just frequency.
 */
@Entity
public class AccessLog {
    @Id
    public long id;

    public long fileId;
    public long conversationId;
    public long accessedAt;
    public boolean wasHelpful;

    public AccessLog() {}

    public AccessLog(long fileId, long conversationId) {
        this.fileId = fileId;
        this.conversationId = conversationId;
        this.accessedAt = System.currentTimeMillis();
        this.wasHelpful = false;
    }
}
