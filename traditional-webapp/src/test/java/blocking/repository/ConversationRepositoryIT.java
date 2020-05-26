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
package blocking.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import domain.Conversation;
import domain.Message;
import domain.User;
import net.bytebuddy.utility.RandomString;

public class ConversationRepositoryIT {

    private static final String selectCountConversationsQuery = "SELECT COUNT( * ) FROM Conversation WHERE id=? AND next_message_id=?;";
    private static final String insertUserQuery = "INSERT INTO User ( id, name ) VALUES ( ?, ? );";
    private static final String insertConversationQuery = "INSERT INTO Conversation ( id, next_message_id ) VALUES ( ?, ? );";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private JdbcDataSource dataSource;
    private ConversationRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        final var url = "jdbc:h2:mem:myDb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

        dataSource = new JdbcDataSource();
        dataSource.setUrl(url);

        try (var schema = getClass().getResourceAsStream("/schema.sql")) {
            final var shell = new Shell();
            shell.setIn(schema);
            try (var connection = dataSource.getConnection()) {
                shell.runTool(connection, "-url", url);
            }
        }

        repository = new ConversationRepository(dataSource);
    }

    @AfterEach
    void tearDown() throws Exception {
    }

    @Test
    public final void testFindMessages() throws SQLException {
        // given
        final var random = new Random();
        final var xId = UUID.randomUUID();
        final var yId = UUID.randomUUID();
        final var conversationId = xId.compareTo(yId) < 0 ? repository.composeIds(xId, yId)
                : repository.composeIds(yId, xId);

        try (var connection = dataSource.getConnection()) {
            // insert users
            try( var statement = connection.prepareStatement(insertUserQuery) ) {
                statement.setString(1, xId.toString());
                statement.setString(2, "x");
                statement.addBatch();
                statement.setString(1, yId.toString());
                statement.setString(2, "y");
                statement.addBatch();
                statement.executeBatch();
            }
            // insert  conversation
            try (var statement = connection.prepareStatement(insertConversationQuery)) {
                statement.setObject(1, conversationId);
                statement.setInt(2, Integer.MIN_VALUE);
                assertEquals(1, statement.executeUpdate());
            }
            // insert participants
            try (var statement = connection.prepareStatement(
                    "INSERT INTO Conversation_Participant ( conversation_id, user_id ) VALUES ( ?, ? );")) {
                statement.setString(1, conversationId.toString());
                statement.setString(2, xId.toString());
                statement.addBatch();
                statement.setString(2, yId.toString());
                statement.addBatch();
                statement.executeBatch();
            }
            // insert messages
            try (var statement = connection.prepareStatement(
                    "INSERT INTO Message ( ts, from_id, body, conversation_id, id ) VALUES ( ?, ?, ?, ?, ? );")) {
                statement.setObject(1, OffsetDateTime.now());
                statement.setString(4, conversationId.toString());
                IntStream.range(0, 8).forEach(index -> {
                    try {
                        statement.setString(2, (random.nextBoolean() ? xId : yId).toString());
                        statement.setString(3, RandomString.make(8192));
                        statement.setInt(5, Integer.MIN_VALUE + index);
                        statement.addBatch();
                    } catch (final SQLException se) {
                        logger.error(se.getMessage(), se);
                    }
                });
                statement.executeBatch();
            }
        }

        // when
        final var conversation = new Conversation(conversationId);
        final var result = repository.findMessages(conversation, 3, Integer.MIN_VALUE + 5);

        // then
        assertEquals(3, result.size());
        assertEquals(Integer.MIN_VALUE + 4, result.get(2).getId());
        assertEquals(Integer.MIN_VALUE + 2, result.get(0).getId());
    }

    @Test // TODO test not found scenario
    public final void testFindConversationUUID() throws SQLException {
        // given
        final var id = UUID.randomUUID();
        final var nextMessageId = 17;
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection
                    .prepareStatement(insertConversationQuery)) {
                statement.setString(1, id.toString());
                statement.setInt(2, nextMessageId);
                assertEquals(1, statement.executeUpdate());
            }
        }

        // when
        final var result = repository.findConversation(id);

        // then
        assertEquals(id, result.getId());
        assertEquals(nextMessageId, result.getNextMessageId());
    }

    @Test
    public final void verifyFindOrCreateConversationCreates() throws SQLException {
        // given
        final var sender = new User(UUID.randomUUID(), "sender");
        final var recipient = new User(UUID.randomUUID(), "recipient");
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement(insertUserQuery)) {
                // sender
                statement.setString(1, sender.getId().toString());
                statement.setString(2, sender.getName());
                statement.addBatch();

                // recipient
                statement.setString(1, recipient.getId().toString());
                statement.setString(2, recipient.getName());
                statement.addBatch();

                statement.executeBatch();
            }
        }

        // when
        final var result = repository.findOrCreateConversation(sender, recipient);

        // then
        assertEquals(Integer.MIN_VALUE, result.getNextMessageId());
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT( * ) FROM Conversation_Participant WHERE conversation_id=? AND user_id=?;")) {
                statement.setString(1, result.getId().toString());

                // sender
                statement.setString(2, sender.getId().toString());
                var resultSet = statement.executeQuery();
                resultSet.next();
                assertEquals(1, resultSet.getInt(1));

                // recipient
                statement.setString(2, recipient.getId().toString());
                resultSet = statement.executeQuery();
                resultSet.next();
                assertEquals(1, resultSet.getInt(1));

                resultSet.close();
            }
        }
    }

    @Test
    public final void verifyFindOrCreateConversationFinds() throws SQLException {
        // given
        final var sender = new User(UUID.randomUUID(), "sender");
        final var recipient = new User(UUID.randomUUID(), "recipient");

        final var id = sender.getId().compareTo(recipient.getId()) < 0
                ? repository.composeIds(sender.getId(), recipient.getId())
                : repository.composeIds(recipient.getId(), sender.getId());

        final var nextMessageId = 17;
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement(insertConversationQuery)) {
                statement.setString(1, id.toString());
                statement.setInt(2, nextMessageId);
                assertEquals(1, statement.executeUpdate());
            }
        }

        // when
        final var result = repository.findOrCreateConversation(sender, recipient);

        // then
        assertEquals(nextMessageId, result.getNextMessageId());
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement(selectCountConversationsQuery)) {
                statement.setString(1, result.getId().toString());
                statement.setInt(2, nextMessageId);

                try (var resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt(1));
                    assertFalse(resultSet.next());
                }
            }
        }
    }

    @Test
    public final void testCreateMessage() throws SQLException {
        // given
        final var user = new User(UUID.randomUUID(), "user");
        final var conversation = new Conversation(UUID.randomUUID(), 31);
        final var message = new Message(conversation.getId(), user.getId(), OffsetDateTime.now(),
                RandomString.make(8192));

        try (var connection = dataSource.getConnection()) {
            // insert conversation
            try (var statement = connection.prepareStatement(insertConversationQuery)) {
                statement.setString(1, conversation.getId().toString());
                statement.setInt(2, conversation.getNextMessageId());
                assertEquals(1, statement.executeUpdate());
            }
            // insert user
            try (var statement = connection.prepareStatement(insertUserQuery)) {
                statement.setString(1, user.getId().toString());
                statement.setString(2, user.getName());
                assertEquals(1, statement.executeUpdate());
            }
        }

        // when
        repository.createMessage(conversation, message);

        // then
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT( * ) FROM Message WHERE ts=? AND from_id=? AND body=? AND conversation_id=? AND id=?;")) {
                statement.setObject(1, message.getTimestamp());
                statement.setString(2, user.getId().toString());
                statement.setString(3, message.getBody());
                statement.setString(4, conversation.getId().toString());
                statement.setInt(5, 31);
                try (var resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt(1));
                    assertFalse(resultSet.next());
                }
            }
            try (var statement = connection
                    .prepareStatement("SELECT next_message_id FROM Conversation WHERE id=?;")) {
                statement.setString(1, conversation.getId().toString());
                try (var resultSet = statement.executeQuery()) {
                    assertTrue(resultSet.next());
                    assertEquals(32, resultSet.getInt("next_message_id"));
                    assertFalse(resultSet.next());
                }
            }
        }
    }

}