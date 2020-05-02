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
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.macasaet.Conversation;
import com.macasaet.Message;
import com.macasaet.User;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.util.function.Tuple2;

@Repository
public class ConversationRepository {

    private static final String selectNextMessageIdQuery = "SELECT next_message_id FROM Conversation WHERE id=?;";
    private static final String findMessagesQuery = "SELECT ts, from_id, body, id FROM Message WHERE conversation_id=? AND id<? ORDER BY id DESC LIMIT ?;";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final DataSource dataSource;

    @Autowired
    public ConversationRepository(final DataSource dataSource) {
        Objects.requireNonNull(dataSource);
        this.dataSource = dataSource;
    }

    public Flux<Message> findMessages(final Mono<Conversation> conversation, final int limit, final int before) {
        return conversation.flatMapMany(c -> {
            return Flux.generate(sink -> {
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
                                final var message = new Message(fromId, timestamp, body, id);
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
        return firstParticipant.zipWith(secondParticipant).handle(this::findOrCreateConversation);
    }

    public Mono<Message> findMessage(final Mono<User> sender, final Mono<User> recipient, final int id) {
        return Mono.create(sink -> {
            sender.zipWith(recipient).doOnSuccess(tuple -> {
                final var senderId = tuple.getT1().getId();
                final var recipientId = tuple.getT2().getId();
                final var conversationId = senderId.compareTo(recipientId) < 0 ? composeIds(senderId, recipientId)
                        : composeIds(recipientId, senderId);
                try (var connection = getDataSource().getConnection()) {
                    try (var statement = connection.prepareStatement(
                            "SELECT ts, body FROM Message WHERE conversation_id=? AND id=?;", TYPE_FORWARD_ONLY,
                            CONCUR_READ_ONLY)) {
                        statement.setObject(1, conversationId);
                        statement.setObject(2, id);
                        try (var resultSet = statement.executeQuery()) {
                            if (!resultSet.next()) {
                                sink.success();
                            }
                            final var timestamp = resultSet.getObject("ts", OffsetDateTime.class);
                            final var body = resultSet.getString("body");
                            final var retval = new Message(senderId, timestamp, body, id);
                            if (resultSet.next()) {
                                sink.error(new IllegalStateException(
                                        "Consistency error: multiple matching messages found"));
                            }
                            sink.success(retval);
                        }
                    }
                } catch (final SQLException se) {
                    logger.error(se.getMessage(), se);
                    sink.error(se);
                }
            });
        });

    }

    public Mono<Message> createMessage(final Mono<Conversation> conversation, final Mono<Message> message) {
        return message.handle( ( incoming, sink ) -> {
            try (var connection = getDataSource().getConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

                getAndIncrementMessageId(connection, conversation).doOnSuccess(updatedConversation -> {

                    final var messageId = updatedConversation.getNextMessageId();
                    incoming.setId(messageId);
                    try (var statement = connection.prepareStatement(
                            "INSERT INTO Message ( ts, from_id, body, conversation_id, id ) VALUES ( ?, ?, ?, ?, ? );")) {
                        statement.setObject(1, incoming.getTimestamp());
                        statement.setObject(2, incoming.getFromUserId());
                        statement.setString(3, incoming.getBody());
                        statement.setObject(4, updatedConversation.getId());
                        statement.setInt(5, messageId);
                        final var rowsInserted = statement.executeUpdate();
                        if (rowsInserted != 1) {
                            sink.error(new RuntimeException("Expected 1 row to be inserted, but got: " + rowsInserted));
                        } else {
                            connection.commit();
                            sink.next(incoming);
                        }
                    } catch( final SQLException se ) {
                        logger.error(se.getMessage(), se);
                        sink.error(se);
                    }
                }).doOnError(sink::error);
            } catch (final SQLException se) {
                logger.error(se.getMessage(), se);
                sink.error(se);
            }
        });
    }

    protected void findOrCreateConversation(final Tuple2<User, User> participants,
            final SynchronousSink<Conversation> sink) {
        final var firstParticipantId = participants.getT1().getId();
        final var secondParticipantId = participants.getT2().getId();

        final var compositeId = firstParticipantId.compareTo(secondParticipantId) < 0
                ? composeIds(firstParticipantId, secondParticipantId)
                : composeIds(secondParticipantId, firstParticipantId);
        try (var connection = getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try (var insertStatement = connection.prepareStatement(
                    "INSERT INTO Conversation ( id, next_message_id ) VALUES ( ?, ? );", ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_UPDATABLE)) {
                insertStatement.setObject(1, compositeId);
                insertStatement.setInt(2, Integer.MIN_VALUE);
                try {
                    final var rowsUpdated = insertStatement.executeUpdate();
                    if (rowsUpdated == 1) {
                        for (final var id : new UUID[] { firstParticipantId, secondParticipantId }) {
                            insertRelation(compositeId, connection, id);
                        }
                        connection.commit();
                    } else if (rowsUpdated < 0 || rowsUpdated > 1) {
                        connection.rollback();
                        sink.error(new IllegalStateException(
                                "consistency error: expected either 0 or 1 conversations inserted"));
                        return;
                    }
                } catch (final SQLIntegrityConstraintViolationException sicve) {
                    // FIXME maybe should leverage transaction instead
                    logger.debug("Conversation already exists: " + sicve.getMessage(), sicve);
                }
                findConversation(connection, compositeId, sink);
            }
        } catch (final SQLException se) {
            logger.error(se.getMessage(), se);
            sink.error(se);
        }
    }

    protected void insertRelation(final UUID compositeId, Connection connection, final UUID participantId) throws SQLException {
        try (var relateStatement = connection.prepareStatement(
                "INSERT INTO Conversation_Participant ( conversation_id, user_id ) VALUES ( ?, ? );",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
            relateStatement.setObject(1, compositeId);
            relateStatement.setObject(2, participantId);
            final var rowsInserted = relateStatement.executeUpdate();
            if( rowsInserted != 1 ) {
                connection.rollback();
                throw new RuntimeException("consistency error: multiple conversation participants created");
            }
        }
    }

    protected Mono<Conversation> getAndIncrementMessageId(final Connection connection,
            final Mono<Conversation> conversation) {
        return conversation.handle((incoming, sink) -> {
            try (var statement = connection.prepareStatement("SELECT id, next_message_id FROM Conversation WHERE id=?;",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
                statement.setObject(1, incoming.getId());
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        sink.error(new IllegalArgumentException("Conversation not found: " + incoming.getId()));
                        return;
                    }
                    final var retval = resultSet.getInt("next_message_id");
                    final int nextMessageId = retval + 1;
                    resultSet.updateInt("next_message_id", nextMessageId);
                    resultSet.updateRow();
                    if (resultSet.next()) {
                        sink.error(
                                new IllegalStateException("Multiple conversations found with id: " + incoming.getId()));
                        return;
                    }
                    incoming.setNextMessageId(nextMessageId);
                    // release lock so other processes can query the table, may create holes
                    // in the sequence if subsequent commands fail
                    connection.commit();
                    sink.next(incoming);
                }
            } catch (final SQLException se) {
                logger.error(se.getMessage(), se);
                sink.error(se);
            }
        });
    }

    protected void findConversation(final Connection connection, final UUID compositeId,
            final SynchronousSink<Conversation> sink) throws SQLException {
        try (var selectQuery = connection.prepareStatement(selectNextMessageIdQuery, ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY)) {
            selectQuery.setObject(1, compositeId);
            try (var resultSet = selectQuery.executeQuery()) {
                if (!resultSet.next()) {
                    connection.rollback();
                    sink.error(new IllegalArgumentException("consistency error: Conversation should already exist"));
                    return;
                }
                final var nextMessageId = resultSet.getInt("next_message_id");
                final var retval = new Conversation(compositeId, nextMessageId);
                if (resultSet.next()) {
                    connection.rollback();
                    sink.error(new IllegalStateException("consistency error: multiple conversations with same id"));
                    return;
                }
                sink.next(retval);
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