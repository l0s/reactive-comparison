package domain;

import java.util.Objects;
import java.util.UUID;

public class Conversation {

    private final UUID id;
    private int nextMessageId = Integer.MIN_VALUE;

    public Conversation(final UUID id) {
        Objects.requireNonNull(id);
        this.id = id;
    }

    public Conversation(final UUID id, final int nextMessageId) {
        this(id);
        setNextMessageId(nextMessageId);
    }

    public int getNextMessageId() {
        return nextMessageId;
    }

    public void setNextMessageId(int nextMessageId) {
        this.nextMessageId = nextMessageId;
    }

    public UUID getId() {
        return id;
    }

    public String toString() {
        final var builder = new StringBuilder();
        builder.append("Conversation [id=");
        builder.append(getId());
        builder.append(", nextMessageId=");
        builder.append(getNextMessageId());
        builder.append("]");
        return builder.toString();
    }

}