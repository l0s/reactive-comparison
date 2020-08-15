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
package async.repository;

import java.util.Objects;
import java.util.UUID;

public class ConversationCursor {

    public static enum Direction {
        BEFORE('<', "DESC"),
        AFTER('>', "ASC");

        private final char operator;
        private final String order;
        
        private Direction(final char operator, final String order) {
            this.operator = operator;
            this.order = order;
        }

        /*package private*/ char getOperator() {
            return operator;
        }

        /*package private*/ String getOrder() {
            return order;
        }
    }

    private final Direction direction;
    private final UUID referenceId;

    public ConversationCursor(final Direction direction, final UUID referenceId) {
        Objects.requireNonNull(direction, "direction cannot be null");
        Objects.requireNonNull(referenceId, "referenceId cannot be null");
        this.direction = direction;
        this.referenceId = referenceId;
    }

    public String genClause(final String columnId) {
        if (columnId == null || columnId.isBlank()) {
            throw new IllegalArgumentException("columnId must be specified");
        }
        return columnId + getDirection().getOperator() + "?";
    }

    public String genOrdering(final String columnId) {
        if (columnId == null || columnId.isBlank()) {
            throw new IllegalArgumentException("columnId must be specified");
        }
        return "ORDER BY " + columnId + " " + getDirection().getOrder();
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public Direction getDirection() {
        return direction;
    }

    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("ConversationCursor [direction=");
        builder.append(direction);
        builder.append(", referenceId=");
        builder.append(referenceId);
        builder.append("]");
        return builder.toString();
    }

}