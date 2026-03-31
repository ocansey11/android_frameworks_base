package com.android.server.rag.model;

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

    public String content;
    public boolean isUser;
    public long timestamp;
    public String source;           // "text", "voice", or "tool"

    // Relation
    public ToOne<Conversation> conversation;

    public Message() {}

    public Message(String content, boolean isUser, String source) {
        this.content = content;
        this.isUser = isUser;
        this.source = source;
        this.timestamp = System.currentTimeMillis();
    }
}
