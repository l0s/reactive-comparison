package com.macasaet.messaging.repository;

import static java.sql.ResultSet.CONCUR_READ_ONLY;
import static java.sql.ResultSet.TYPE_FORWARD_ONLY;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
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

    public List<Message> findMessages(final Conversation conversation, final int limit, final int before) {
        try (var connection = getDataSource().getConnection()) {
            try (var statement = connection.prepareStatement(findMessagesQuery, TYPE_FORWARD_ONLY, CONCUR_READ_ONLY)) {
                statement.setString(1, conversation.getId().toString());
                statement.setInt(2, before);
                statement.setInt(3, limit);
                try (var resultSet = statement.executeQuery()) {
                    final var list = new LinkedList<Message>();
                    while (resultSet.next()) {
                        final var timestamp = resultSet.getObject("ts", OffsetDateTime.class);
                        final var fromId = resultSet.getObject("from_id", UUID.class);
                        final var body = resultSet.getString("body");
                        final var id = resultSet.getInt("id");
                        final var message = new Message(fromId, timestamp, body, id);
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

    public Conversation findConversation(final UUID id) {
        try (var connection = getDataSource().getConnection()) {
            try (var statement = connection.prepareStatement(selectNextMessageIdQuery, TYPE_FORWARD_ONLY,
                    CONCUR_READ_ONLY)) {
                statement.setString(1, id.toString());
                try (var resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        final var nextMessageId = resultSet.getInt("next_message_id");
                        final var retval = new Conversation(id, nextMessageId);
                        if (resultSet.next()) {
                            throw new RuntimeException("Multiple conversations found with same id: " + id);
                        }
                        return retval;
                    } else {
                        logger.warn("No conversation found with id: {}", id);
                        return null;
                    }
                }
            }
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
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try (var insertStatement = connection.prepareStatement(
                    "INSERT INTO Conversation ( id, next_message_id ) VALUES ( ?, ? );", ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_UPDATABLE)) {
                insertStatement.setString(1, compositeId.toString());
                insertStatement.setInt(2, Integer.MIN_VALUE);
                try {
                    final var rowsUpdated = insertStatement.executeUpdate();
                    if (rowsUpdated == 1) {
                        for (final var id : new UUID[] { firstParticipantId, secondParticipantId }) {
                            insertRelation(compositeId, connection, id);
                        }
                    } else if (rowsUpdated < 0 || rowsUpdated > 1) {
                        connection.rollback();
                        throw new RuntimeException("consistency error: expected either 0 or 1 conversations inserted");
                    }
                    connection.commit();
                } catch (final SQLIntegrityConstraintViolationException sicve) {
                    // FIXME maybe should leverage transaction instead
                    logger.debug("Conversation already exists: " + sicve.getMessage(), sicve);
                }
                return findConversation(connection, compositeId);
            }
        } catch (final SQLException se) {
            logger.error(se.getMessage(), se);
            throw new RuntimeException("Error obtaining conversation between " + firstParticipant + " and "
                    + secondParticipant + ": " + se.getMessage(), se);
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
                statement.setString(1, conversationId.toString());
                statement.setInt(2, id);
                try (var resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return null;
                    }
                    final var timestamp = resultSet.getObject("ts", OffsetDateTime.class);
                    final var body = resultSet.getString("body");
                    final var retval = new Message(senderId, timestamp, body, id);
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

    public void createMessage(final Conversation conversation, final Message message) {
        try (var connection = getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            final var messageId = getAndIncrementMessageId(connection, conversation);
            // release lock so other processes can query the table, may create holes
            // in the sequence if subsequent commands fail
            connection.commit();

            message.setId(messageId);
            try (var statement = connection.prepareStatement(
                    "INSERT INTO Message ( ts, from_id, body, conversation_id, id ) VALUES ( ?, ?, ?, ?, ? );")) {
                statement.setObject(1, message.getTimestamp());
                statement.setString(2, message.getFromUserId().toString());
                statement.setString(3, message.getBody());
                statement.setString(4, conversation.getId().toString());
                statement.setInt(5, messageId);
                final var rowsInserted = statement.executeUpdate();
                if (rowsInserted != 1) {
                    throw new RuntimeException("Expected 1 row to be inserted, but got: " + rowsInserted);
                }
                connection.commit();
            }
        } catch (final SQLException se) {
            logger.error(se.getMessage(), se);
            throw new RuntimeException("Error creating message: " + se.getMessage(), se);
        }
    }

    protected void insertRelation(final UUID compositeId, Connection connection, final UUID participantId) throws SQLException {
        try (var relateStatement = connection.prepareStatement(
                "INSERT INTO Conversation_Participant ( conversation_id, user_id ) VALUES ( ?, ? );",
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE)) {
            relateStatement.setString(1, compositeId.toString());
            relateStatement.setString(2, participantId.toString());
            final var rowsInserted = relateStatement.executeUpdate();
            if( rowsInserted != 1 ) {
                connection.rollback();
                throw new RuntimeException("consistency error: multiple conversation participants created");
            }
        }
    }

    protected int getAndIncrementMessageId(final Connection connection, final Conversation conversation)
            throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id, next_message_id FROM Conversation WHERE id=?;", ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_UPDATABLE)) {
            statement.setString(1, conversation.getId().toString());
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Conversation not found: " + conversation.getId());
                }
                final var retval = resultSet.getInt("next_message_id");
                final int nextMessageId = retval + 1;
                resultSet.updateInt("next_message_id", nextMessageId);
                resultSet.updateRow();
                if (resultSet.next()) {
                    throw new IllegalStateException("Multiple conversations found with id: " + conversation.getId());
                }
                conversation.setNextMessageId(nextMessageId);
                return retval;
            }
        }
    }

    protected Conversation findConversation(final Connection connection, final UUID compositeId) throws SQLException {
        try (var selectQuery = connection.prepareStatement(selectNextMessageIdQuery,
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            selectQuery.setString(1, compositeId.toString());
            try (var resultSet = selectQuery.executeQuery()) {
                if (!resultSet.next()) {
                    connection.rollback();
                    throw new RuntimeException("consistency error: Conversation should already exist");
                }
                final var nextMessageId = resultSet.getInt("next_message_id");
                final var retval = new Conversation(compositeId, nextMessageId);
                if (resultSet.next()) {
                    connection.rollback();
                    throw new RuntimeException("consistency error: multiple conversations with same id");
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