package com.macasaet.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

public class JdbcTest {

    private static final DatabaseServer server = new DatabaseServer();

    private DataSource dataSource;
//    private UserRepository userRepository;

    @BeforeAll
    public static void setUpServer() throws SQLException, IOException {
        server.start();
    }

    @AfterAll
    public static void tearDownServer() {
        server.stop();
    }

    @BeforeEach
    public void setUp() {
        final var dataSource =  new PGSimpleDataSource();
        dataSource.setUrl("jdbc:postgresql://localhost:5435/db");
        dataSource.setUser("");
        dataSource.setPassword("");
        this.dataSource = dataSource;

//        userRepository = new UserRepository(dataSource);
    }

    @AfterEach
    public void tearDown() {
//        userRepository = null;
        dataSource = null;
    }

    @Test
    public final void verifyConnection() throws SQLException {
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareCall("SELECT COUNT( * ) FROM Message;")) {
                try (var resultSet = statement.executeQuery()) {
                    final var metaData = resultSet.getMetaData();
                    assertEquals(1, metaData.getColumnCount());
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getRow());
                    assertEquals("count(*)", metaData.getColumnLabel(1));
                    assertEquals(0, resultSet.getInt(1));
                    assertFalse(resultSet.next());
                }
            }
        }
    }
//
//    @Test
//    public final void testCreationFind() {
//        // given
//        final var user = new User("alice");
//
//        // when
//        userRepository.createUser(user);
//
//        // then
//        final var mono = userRepository.findById(user.getId());
//        final var found = mono.block();
//        assertEquals(user.getId(), found.getId());
//        assertEquals(user.getName(), found.getName());
//    }

}