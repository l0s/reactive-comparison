package domain;

import java.util.UUID;

public class User {

    private final UUID id;
    private final String name;

    public User(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public User(final String name) {
        this(UUID.randomUUID(), name);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String toString() {
        final var builder = new StringBuilder();
        builder.append("User [id=");
        builder.append(getId());
        builder.append(", name=");
        builder.append(getName());
        builder.append("]");
        return builder.toString();
    }

}