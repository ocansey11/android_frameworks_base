package com.android.server.rag.model;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.relation.ToMany;

/**
 * A conversation session with JarvisOS.
 */
@Entity
public class Conversation {
    @Id
    public long id;

    public String title;
    public long createdAt;
    public long lastMessageAt;
    public String lastMessagePreview;
    public String mode;             // "text" or "voice"

    // Relations
    public ToMany<Message> messages;
    public ToMany<SourceFile> referencedFiles;

    public Conversation() {}

    public Conversation(String title, String mode) {
        this.title = title;
        this.mode = mode;
        this.createdAt = System.currentTimeMillis();
        this.lastMessageAt = this.createdAt;
    }
}
