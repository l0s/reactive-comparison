package domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public class Message {

    private final UUID fromUserId;
    private final OffsetDateTime timestamp;
    private final UUID conversationId;
    private String body;
    private int id;

    public Message(final UUID conversationId, final UUID fromUserId, final OffsetDateTime timestamp) {
        Objects.requireNonNull(fromUserId);
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(conversationId);
        this.fromUserId = fromUserId;
        this.timestamp = timestamp;
        this.conversationId = conversationId;
    }

    public Message(final UUID conversationId, final UUID fromUserId, final OffsetDateTime timestamp,
            final String body) {
        this(conversationId, fromUserId, timestamp);
        setBody(body);
    }

    public Message(final UUID conversationId, final UUID fromUserId, final OffsetDateTime timestamp, final String body,
            final int id) {
        this(conversationId, fromUserId, timestamp, body);
        setId(id);
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        Objects.requireNonNull(body);
        this.body = body;
    }

    public UUID getFromUserId() {
        return fromUserId;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String toString() {
        final var builder = new StringBuilder();
        builder.append("Message [conversationId=");
        builder.append(getConversationId());
        builder.append(", fromUserId=");
        builder.append(getFromUserId());
        builder.append(", timestamp=");
        builder.append(getTimestamp());
        builder.append(", id=");
        builder.append(getId());
        builder.append("]");
        return builder.toString();
    }

    public UUID getConversationId() {
        return conversationId;
    }

}