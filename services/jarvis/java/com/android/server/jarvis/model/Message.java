package com.android.server.jarvis.model;

import io.objectbox.BoxStore;
import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;
import io.objectbox.relation.ToOne;

/**
 * Individual message within a Conversation.
 */
@Entity
public class Message {
    @Id
    public long id;

    /** Set by the generated cursor; the relation fields below need it to resolve their targets. */
    transient BoxStore __boxStore;

    public String content;
    public boolean isUser;
    public long timestamp;
    public String source;           // "text", "voice", or "tool"

    // Relation
    public ToOne<Conversation> conversation = new ToOne<>(this, Message_.conversation);

    public Message() {}

    public Message(String content, boolean isUser, String source) {
        this.content = content;
        this.isUser = isUser;
        this.source = source;
        this.timestamp = System.currentTimeMillis();
    }
}
