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

import static java.sql.ResultSet.CONCUR_READ_ONLY;
import static java.sql.ResultSet.TYPE_FORWARD_ONLY;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import domain.Conversation;
import domain.Message;
import domain.User;
import repository.ConversationCursor;
import repository.Direction;
import sync.LockFactory;

@Repository
public class ConversationRepository {

    private static final String selectNextMessageIdQuery = "SELECT next_message_id FROM Conversation WHERE id=?;";
    private static final String findMessagesQuery = "SELECT ts, from_id, body, id FROM Message WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT ?;";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DataSource dataSource;

    private final LockFactory<UUID> lockFactory = new LockFactory<>();

    @Autowired
    public ConversationRepository(final DataSource dataSource) {
        Objects.requireNonNull(dataSource);
        this.dataSource = dataSource;
    }

    public List<Message> findMessages(final Conversation conversation, final int limit, final int before) {
        try (var connection = getDataSource().getConnection()) {
            try (var statement = connection.prepareStatement(findMessagesQuery, TYPE_FORWARD_ONLY, CONCUR_READ_ONLY)) {
                statement.setObject(1, conversation.getId());
                statement.setInt(2, before);
                statement.setInt(3, limit);
                try (var resultSet = statement.executeQuery()) {
                    final var list = new LinkedList<Message>();
                    while (resultSet.next()) {
                        final var timestamp = resultSet.getObject("ts", OffsetDateTime.class);
                        final var fromId = resultSet.getObject("from_id", UUID.class);
                        final var body = resultSet.getString("body");
                        final var id = resultSet.getInt("id");
                        final var message = new Message(conversation.getId(), fromId, timestamp, body, id);
                        list.addFirst(message);
                    }
                    return Collections.unmodifiableList(list);
                }
            }
        } catch (final SQLException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public Message findMessage(final UUID conversationId, final int messageId) {
        // FIXME should first param be domain object?
        try (var connection = getDataSource().getConnection()) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(
                    "SELECT ts, from_id, body FROM Message WHERE conversation_id=? AND id=?;",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setObject(1, conversationId);
                statement.setInt(2, messageId);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        logger.debug("No message found for conversation {} and id {}", conversationId, messageId);
                        return null;
                    }
                    final var timestamp = resultSet.getObject(1, OffsetDateTime.class);
                    final UUID fromUserId = resultSet.getObject(2, UUID.class);
                    final String body = resultSet.getString(3);
                    final var retval = new Message(conversationId, fromUserId, timestamp, body, messageId);
                    if (resultSet.next()) {
                        throw new IllegalStateException("Consistency error, multiple messages with primary key: "
                                + conversationId + ":" + messageId);
                    }
                    return retval;
                }
            }
        } catch (final SQLException se) {
            logger.error(se.getMessage(), se);
            throw new RuntimeException(se.getMessage(), se);
        }
    }

    public Conversation findConversation(final UUID id) {
            try (var connection = getDataSource().getConnection()) {
                return findConversation(connection, id);
            } catch (final SQLException e) {
                logger.error(e.getMessage(), e);
                throw new RuntimeException(e.getMessage(), e);
            }
    }

    public Conversation findOrCreateConversation(final User firstParticipant, final User secondParticipant) {
        final var firstParticipantId = firstParticipant.getId();
        final var secondParticipantId = secondParticipant.getId();
        final var compositeId = firstParticipantId.compareTo(secondParticipantId) < 0
                ? composeIds(firstParticipantId, secondParticipantId)
                : composeIds(secondParticipantId, firstParticipantId);
        try (var connection = getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            final var lock = lockFactory.getLock(compositeId);
            final var readStamp = lock.readLock();
            try {
                var conversation = findConversation(connection, compositeId);
                if (conversation != null) {
                    return conversation;
                }
            } finally {
                lock.unlockRead(readStamp);
            }
            final var writeStamp = lock.writeLock();
            try {
                // check for the conversation again just in case another thread created
                // it after we released the read lock and before we acquired the write
                // lock
                final var conversation = findConversation(connection, compositeId);
                if (conversation != null) {
                    return conversation;
                }
                final var retval = new Conversation(compositeId, Integer.MIN_VALUE);
                try (var insertStatement = connection.prepareStatement(
                        "INSERT INTO Conversation ( id, next_message_id ) VALUES ( ?, ? );",
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
                    insertStatement.setObject(1, retval.getId());
                    insertStatement.setInt(2, retval.getNextMessageId());
                    final var rowsUpdated = insertStatement.executeUpdate();
                    if (rowsUpdated == 1) {
                        try (var relateStatement = connection.prepareStatement(
                                "INSERT INTO Conversation_Participant ( conversation_id, user_id ) VALUES ( ?, ? );",
                                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
                            relateStatement.setObject(1, compositeId);
                            relateStatement.setObject(2, firstParticipantId);
                            relateStatement.addBatch();
                            relateStatement.setObject(1, compositeId);
                            relateStatement.setObject(2, secondParticipantId);
                            relateStatement.addBatch();
                            final var batchResult = relateStatement.executeBatch();
                            if (batchResult.length != 2 && batchResult[0] != 1 && batchResult[1] != 1) {
                                connection.rollback();
                                throw new RuntimeException(
                                        "consistency error: multiple conversation participants created");
                            }
                        }
                    } else if (rowsUpdated < 0 || rowsUpdated > 1) {
                        connection.rollback();
                        throw new RuntimeException("consistency error: expected either 0 or 1 conversations inserted");
                    }
                    connection.commit();
                }
                return retval;
            } finally {
                lock.unlockWrite(writeStamp);
            }
        } catch (final SQLException se) {
            final var message = "Error obtaining acquiring conversation between " + firstParticipant + " and "
                    + secondParticipant + ": " + se.getMessage();
            logger.error(message, se);
            throw new RuntimeException(message, se);
        }
    }

    public List<Conversation> findConversations(final User user, final int limit,
            final ConversationCursor cursor) {
        Objects.requireNonNull(user, "user cannot be null");
        Objects.requireNonNull(cursor, "cursor cannot be null");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must be non-negative");
        }
        try (var connection = getDataSource().getConnection()) {
            final var queryBuilder = new StringBuilder();
            queryBuilder.append("SELECT c.id, c.next_message_id\n");
            queryBuilder.append("FROM Conversation_Participant p\n");
            queryBuilder.append("  INNER JOIN Conversation c ON c.id=p.conversation_id\n");
            queryBuilder.append("WHERE p.user_id=?\n");
            queryBuilder.append("  AND " + cursor.genClause("c.id"));
            queryBuilder.append(cursor.genOrdering("c.id")).append('\n');
            queryBuilder.append("LIMIT ?;");
            try (var statement = connection.prepareStatement(queryBuilder.toString())) {
                statement.setObject(1, user.getId());
                statement.setObject(2, cursor.getReferenceId());
                statement.setObject(3, limit);
                try (var resultSet = statement.executeQuery()) {
                    final var list = new ArrayList<Conversation>();
                    while (resultSet.next()) {
                        final var conversation = new Conversation(resultSet.getObject("id", UUID.class),
                                resultSet.getInt("next_message_id"));
                        list.add(conversation);
                    }
                    if (cursor.getDirection() == Direction.BEFORE) {
                        Collections.reverse(list);
                    }
                    return Collections.unmodifiableList(list);
                }
            }
        } catch (final SQLException se) {
            MDC.put("userId", user.getId().toString());
            MDC.put("limit", "" + limit);
            MDC.put("cursor", cursor.toString());
            MDC.put("causeMessage", se.getMessage());
            logger.error("Error finding conversations for user", se);
            throw new RuntimeException(se.getMessage(), se);
        }
    }

    public Message findMessage(final User sender, final User recipient, final int id) {
        final var senderId = sender.getId();
        final var recipientId = recipient.getId();
        final var conversationId = senderId.compareTo(recipientId) < 0 ? composeIds(senderId, recipientId)
                : composeIds(recipientId, senderId);
        try (var connection = getDataSource().getConnection()) {
            try (var statement = connection.prepareStatement(
                    "SELECT ts, body FROM Message WHERE conversation_id=? AND id=?;", TYPE_FORWARD_ONLY,
                    CONCUR_READ_ONLY)) {
                statement.setObject(1, conversationId);
                statement.setInt(2, id);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return null;
                    }
                    final var timestamp = resultSet.getObject("ts", OffsetDateTime.class);
                    final var body = resultSet.getString("body");
                    final var retval = new Message(conversationId, senderId, timestamp, body, id);
                    if (resultSet.next()) {
                        throw new IllegalStateException("Consistency error: multiple matching messages found");
                    }
                    return retval;
                }
            }
        } catch (final SQLException se) {
            logger.error(se.getMessage(), se);
            throw new RuntimeException("Error finding message: " + se.getMessage(), se);
        }
    }

    public Message createMessage(final Conversation conversation, final Message message) {
        try (var connection = getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            final var messageId = getAndIncrementMessageId(connection, conversation);
            message.setId(messageId);
            try (var statement = connection.prepareStatement(
                    "INSERT INTO Message ( ts, from_id, body, conversation_id, id ) VALUES ( ?, ?, ?, ?, ? );")) {
                statement.setObject(1, message.getTimestamp());
                statement.setObject(2, message.getFromUserId());
                statement.setString(3, message.getBody());
                statement.setObject(4, conversation.getId());
                statement.setInt(5, messageId);
                final var rowsInserted = statement.executeUpdate();
                if (rowsInserted != 1) {
                    // roll back message creation but not message ID increment
                    connection.rollback();
                    throw new RuntimeException("Expected 1 row to be inserted, but got: " + rowsInserted);
                }
                connection.commit();
                return message;
            }
        } catch (final SQLException se) {
            logger.error(se.getMessage(), se);
            throw new RuntimeException("Error creating message: " + se.getMessage(), se);
        }
    }

    protected int getAndIncrementMessageId(final Connection connection, final Conversation conversation)
            throws SQLException {
        final var lock = lockFactory.getLock(conversation.getId());
        final var stamp = lock.writeLock();
        try {
            final var query = "UPDATE Conversation SET next_message_id=next_message_id + 1 WHERE id=? RETURNING next_message_id - 1 AS current, next_message_id AS next;";
            try (var statement = connection.prepareStatement(query, ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY)) {
                statement.setObject(1, conversation.getId());
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("Cannot find conversation with id: " + conversation.getId());
                    }
                    final var retval = resultSet.getInt("current");
                    final var next = resultSet.getInt("next");
                    if (resultSet.next()) {
                        throw new IllegalStateException(
                                "Multiple matching conversations found for id: " + conversation.getId());
                    }
                    // release lock so other processes can query the table
                    connection.commit();
                    conversation.setNextMessageId(next);
                    return retval;
                }
            }
        } finally {
            lock.unlockWrite(stamp);
        }
    }

    protected Conversation findConversation(final Connection connection, final UUID conversationId) throws SQLException {
        try (var selectQuery = connection.prepareStatement(selectNextMessageIdQuery, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
            selectQuery.setObject(1, conversationId);
            try (var resultSet = selectQuery.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                final var nextMessageId = resultSet.getInt("next_message_id");
                final var retval = new Conversation(conversationId, nextMessageId);
                if (resultSet.next()) {
                    throw new RuntimeException(
                            "consistency error: multiple conversations with same id: " + conversationId);
                }
                return retval;
            }
        }
    }

    protected UUID composeIds(final UUID first, final UUID second) {
        try (var outputStream = new ByteArrayOutputStream(Long.BYTES * 4)) {
            try (var output = new DataOutputStream(outputStream)) {
                output.writeLong(first.getMostSignificantBits());
                output.writeLong(first.getLeastSignificantBits());
                output.writeLong(second.getMostSignificantBits());
                output.writeLong(second.getLeastSignificantBits());
            }
            return UUID.nameUUIDFromBytes(outputStream.toByteArray());
        } catch (final IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    protected DataSource getDataSource() {
        return dataSource;
    }

}