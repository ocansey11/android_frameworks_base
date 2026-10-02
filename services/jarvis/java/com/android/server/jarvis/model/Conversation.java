package com.android.server.jarvis.model;

import io.objectbox.BoxStore;
import io.objectbox.annotation.Backlink;
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

    /** Set by the generated cursor; the relation fields below need it to resolve their targets. */
    transient BoxStore __boxStore;

    public String title;
    public long createdAt;
    public long lastMessageAt;
    public String lastMessagePreview;
    public String mode;             // "text" or "voice"

    // Relations
    @Backlink(to = "conversation")
    public ToMany<Message> messages = new ToMany<>(this, Conversation_.messages);
    public ToMany<SourceFile> referencedFiles = new ToMany<>(this, Conversation_.referencedFiles);

    public Conversation() {}

    public Conversation(String title, String mode) {
        this.title = title;
        this.mode = mode;
        this.createdAt = System.currentTimeMillis();
        this.lastMessageAt = this.createdAt;
    }
}
