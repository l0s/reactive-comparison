package reactive.repository;

import static java.sql.ResultSet.CONCUR_READ_ONLY;
import static java.sql.ResultSet.TYPE_FORWARD_ONLY;
import static java.sql.ResultSet.TYPE_SCROLL_SENSITIVE;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import domain.Conversation;
import domain.Message;
import domain.User;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import sync.LockFactory;

@CircuitBreaker(name="conversationRepository")
@Retry(name="conversationRepository")
@Repository
public class ConversationRepository {

    private static final String selectNextMessageIdQuery = "SELECT next_message_id FROM Conversation WHERE id=?;";
    private static final String findMessagesQuery = "SELECT ts, from_id, body, id FROM Message WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT ?;";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final LockFactory<UUID> lockFactory = new LockFactory<>();

    private final DataSource dataSource;

    @Autowired
    public ConversationRepository(final DataSource dataSource) {
        Objects.requireNonNull(dataSource);
        this.dataSource = dataSource;
    }

    public Flux<Message> findMessages(final Mono<Conversation> conversation, final int limit, final int before) {
        return conversation.flatMapMany(c -> {
            return Flux.create(sink -> {
                try (var connection = getDataSource().getConnection()) {
                    try (var statement = connection.prepareStatement(findMessagesQuery, TYPE_SCROLL_SENSITIVE,
                            CONCUR_READ_ONLY)) {
                        statement.setObject(1, c.getId());
                        statement.setInt(2, before);
                        statement.setInt(3, limit);

                        try (var resultSet = statement.executeQuery()) {
                            resultSet.afterLast();
                            while (resultSet.previous()) {
                                final var timestamp = resultSet.getObject("ts", OffsetDateTime.class);
                                final var fromId = resultSet.getObject("from_id", UUID.class);
                                final var body = resultSet.getString("body");
                                final var id = resultSet.getInt("id");
                                final var message = new Message(c.getId(), fromId, timestamp, body, id);
                                sink.next(message);
                            }
                            sink.complete();
                        }
                    }
                } catch (final SQLException se) {
                    logger.error("Error finding for conversation: " + c + ": " + se.getMessage(), se);
                    sink.error(se);
                }
            });
        });
    }

    public Mono<Conversation> findConversation(final UUID id) {
        return Mono.create(sink -> {
            try (var connection = getDataSource().getConnection()) {
                try (var statement = connection.prepareStatement(selectNextMessageIdQuery, TYPE_FORWARD_ONLY,
                        CONCUR_READ_ONLY)) {
                    statement.setObject(1, id);
                    try (var resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            final var nextMessageId = resultSet.getInt("next_message_id");
                            final var retval = new Conversation(id, nextMessageId);
                            if (resultSet.next()) {
                                sink.error(
                                        new IllegalStateException("Multiple conversations found with same id: " + id));
                            }
                            sink.success(retval);
                        } else {
                            logger.warn("No conversation found with id: {}", id);
                            sink.success();
                        }
                    }
                }
            } catch (final SQLException e) {
                logger.error("Error finding for id: " + id + ": " + e.getMessage(), e);
                sink.error(e);
            }
        });
    }

    public Mono<Conversation> findOrCreateConversation(final Mono<User> firstParticipant,
            final Mono<User> secondParticipant) {
        return firstParticipant.zipWith(secondParticipant, (first, second) -> {
            final var firstParticipantId = first.getId();
            final var secondParticipantId = second.getId();

            final var conversationId = firstParticipantId.compareTo(secondParticipantId) < 0
                    ? composeIds(firstParticipantId, secondParticipantId)
                    : composeIds(secondParticipantId, firstParticipantId);

            try (var connection = getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

                final var lock = lockFactory.getLock(conversationId);
                lock.readLock().lock();
                try {
                    try (var findStatement = connection.prepareStatement(selectNextMessageIdQuery)) {
                        findStatement.setObject(1, conversationId);
                        try (var resultSet = findStatement.executeQuery()) {
                            if (resultSet.next()) {
                                return new Conversation(conversationId, resultSet.getInt(1));
                            }
                        }
                    }
                } finally {
                    lock.readLock().unlock();
                }

                lock.writeLock().lock();
                try {
                    // check for the conversation again just in case another thread created
                    // it after we released the read lock and before we acquired the write
                    // lock
                    try (var findStatement = connection.prepareStatement(selectNextMessageIdQuery)) {
                        findStatement.setObject(1, conversationId);
                        try (var resultSet = findStatement.executeQuery()) {
                            if (resultSet.next()) {
                                return new Conversation(conversationId, resultSet.getInt(1));
                            }
                        }
                    }
                    final var retval = new Conversation(conversationId, Integer.MIN_VALUE);
                    try (var insertStatement = connection.prepareStatement(
                            "INSERT INTO Conversation ( id, next_message_id ) VALUES ( ?, ? );",
                            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
                        insertStatement.setObject(1, retval.getId());
                        insertStatement.setInt(2, retval.getNextMessageId());

                        final var rowsUpdated = insertStatement.executeUpdate();
                        if (rowsUpdated < 0 || rowsUpdated > 1) {
                            connection.rollback();
                            throw new IllegalStateException(
                                    "consistency error: expected either 0 or 1 conversations inserted");
                        }
                        try (var relateStatement = connection.prepareStatement(
                                "INSERT INTO Conversation_Participant ( conversation_id, user_id ) VALUES ( ?, ? );",
                                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
                            relateStatement.setObject(1, conversationId);
                            relateStatement.setObject(2, firstParticipantId);
                            relateStatement.addBatch();
                            relateStatement.setObject(1, conversationId);
                            relateStatement.setObject(2, secondParticipantId);
                            relateStatement.addBatch();
                            final var batchResult = relateStatement.executeBatch();
                            if (batchResult.length != 2 && batchResult[0] != 1 && batchResult[1] != 1) {
                                connection.rollback();
                                throw new RuntimeException(
                                        "consistency error: multiple conversation participants created");
                            }
                        }
                        connection.commit();
                    }
                    return retval;
                } finally {
                    lock.writeLock().unlock();
                }
            } catch (final SQLException se) {
                final var message = "Error obtaining conversation between " + firstParticipantId + " and "
                        + firstParticipantId + ": " + se.getMessage();
                logger.error(message, se);
                throw new RuntimeException(message, se);
            }
        });
    }

    public Mono<Message> findMessage(final UUID conversationId, final int messageId) {
        return Mono.create(sink -> {
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
                            sink.success();
                        }
                        final var timestamp = resultSet.getObject(1, OffsetDateTime.class);
                        final UUID fromUserId = resultSet.getObject(2, UUID.class);
                        final String body = resultSet.getString(3);
                        final var retval = new Message(conversationId, fromUserId, timestamp, body, messageId);
                        if (resultSet.next()) {
                            sink.error(
                                    new IllegalStateException("Consistency error, multiple messages with primary key: "
                                            + conversationId + ":" + messageId));
                        }
                        sink.success(retval);
                    }
                }
            } catch (final SQLException se) {
                logger.error(se.getMessage(), se);
                sink.error(se);
            }
        });
    }

    public Mono<Message> findMessage(final Mono<User> sender, final Mono<User> recipient, final int id) {
        return sender.zipWith(recipient).map(tuple -> {
            final var senderId = tuple.getT1().getId();
            final var recipientId = tuple.getT2().getId();
            return senderId.compareTo(recipientId) < 0
                ? composeIds(senderId, recipientId)
                : composeIds(recipientId, senderId);
        })
        .flatMap(compositeId -> findMessage(compositeId, id));
    }

    public Mono<Message> createMessage(final Mono<Conversation> conversation, final Mono<Message> message) {
        return conversation.zipWith(message, (conversationToUpdate, messageToUpdate) -> {
            try (var connection = getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

                final var updatedConversation = getAndIncrementMessageId(connection, conversationToUpdate);

                final var messageId = updatedConversation.getNextMessageId();
                messageToUpdate.setId(messageId);
                try (var statement = connection.prepareStatement(
                        "INSERT INTO Message ( ts, from_id, body, conversation_id, id ) VALUES ( ?, ?, ?, ?, ? );")) {
                    statement.setObject(1, messageToUpdate.getTimestamp());
                    statement.setObject(2, messageToUpdate.getFromUserId());
                    statement.setString(3, messageToUpdate.getBody());
                    statement.setObject(4, updatedConversation.getId());
                    statement.setInt(5, messageId);
                    final var rowsInserted = statement.executeUpdate();
                    if (rowsInserted != 1) {
                        connection.rollback();
                        throw new RuntimeException("Expected 1 row to be inserted, but got: " + rowsInserted);
                    } else {
                        connection.commit();
                        return messageToUpdate;
                    }
                }
            } catch (final SQLException se) {
                logger.error(se.getMessage(), se);
                throw new RuntimeException(se.getMessage(), se);
            }
        });
    }

    protected Conversation getAndIncrementMessageId(final Connection connection, final Conversation conversation) throws SQLException {
        final var lock = lockFactory.getLock(conversation.getId());
        lock.writeLock().lock();
        try {
            try (var statement = connection.prepareStatement("SELECT id, next_message_id FROM Conversation WHERE id=?;",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
                statement.setObject(1, conversation.getId());
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalArgumentException("Conversation not found: " + conversation.getId());
                    }
                    final var retval = resultSet.getInt("next_message_id");
                    final int nextMessageId = retval + 1;
                    resultSet.updateInt("next_message_id", nextMessageId);
                    resultSet.updateRow();
                    if (resultSet.next()) {
                        throw new IllegalStateException(
                                "Multiple conversations found with id: " + conversation.getId());
                    }
                    conversation.setNextMessageId(nextMessageId);
                    // release lock so other processes can query the table, may create holes
                    // in the sequence if subsequent commands fail
                    connection.commit();
                    return conversation;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    protected Conversation findConversation(final Connection connection, final UUID compositeId) throws SQLException {
        try (var selectQuery = connection.prepareStatement(selectNextMessageIdQuery, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
            selectQuery.setObject(1, compositeId);
            try (var resultSet = selectQuery.executeQuery()) {
                if (!resultSet.next()) {
                    connection.rollback();
                    throw new IllegalArgumentException("consistency error: Conversation should already exist");
                }
                final var nextMessageId = resultSet.getInt("next_message_id");
                final var retval = new Conversation(compositeId, nextMessageId);
                if (resultSet.next()) {
                    connection.rollback();
                    throw new IllegalStateException("consistency error: multiple conversations with same id");
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