/**
 * Copyright © 2020 Carlos Macasaet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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