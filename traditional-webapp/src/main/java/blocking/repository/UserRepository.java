package blocking.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import domain.User;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@CircuitBreaker(name = "userRepository")
@Retry(name="userRepository")
@Repository
public class UserRepository {

    static final String selectAllTemplate = "SELECT id, name FROM \"User\" LIMIT ? OFFSET ?;";
    static final String selectByIdTemplate = "SELECT id, name FROM \"User\" WHERE id=?;";
    static final String insertionTemplate = "INSERT INTO \"User\"( id, name ) values( ?, ? );";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final DataSource dataSource;

    @Autowired
    public UserRepository(final DataSource dataSource) {
        Objects.requireNonNull(dataSource);
        this.dataSource = dataSource;
    }

    public void createUser(final User user) {
        try {
            try (var connection = getDataSource().getConnection()) {
                try (var statement = connection.prepareStatement(insertionTemplate)) {
                    statement.setObject(1, user.getId());
                    statement.setString(2, user.getName());
                    statement.executeUpdate();
                }
            }
        } catch (final SQLException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public User findById(final UUID id) {
        try {
            // thread blocks while connection is retrieved from the pool
            try (var connection = getDataSource().getConnection()) {
                try (var statement = connection.prepareStatement(selectByIdTemplate, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                    statement.setObject(1, id);
                    // thread blocks while query is executed
                    try (var resultSet = statement.executeQuery()) {
                        var hasFirstRow = resultSet.next();
                        if (!hasFirstRow) {
                            return null;
                        }
                        final var foundId = UUID.fromString(resultSet.getString(1));
                        final var name = resultSet.getString(2);
                        return new User(foundId, name);
                    }
                }
            }
        } catch (final SQLException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public List<User> findAll(final int pageNumber, final int pageSize) {
        final var offset = pageSize * pageNumber;
        try {
            try (var connection = getDataSource().getConnection()) {
                try (var statement = connection.prepareStatement(selectAllTemplate, ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY)) {
                    statement.setInt(1, pageSize);
                    statement.setInt(2, offset);
                    try (var resultSet = statement.executeQuery()) {
                        // need to allocate memory for the all the records, whether they will be
                        // used or not
                        final var list = new ArrayList<User>(pageSize);
                        // ResultSet / JDBC implements batching internally, but we don't get any
                        // benefit from it because this method blocks until all batches have
                        // been received
                        while (resultSet.next()) {
                            // need to retrieve every record, whether or not the upstream client
                            // will use it
                            final var id = UUID.fromString(resultSet.getString(1));
                            final var name = resultSet.getString(2);
                            list.add(new User(id, name));
                        }
                        return Collections.unmodifiableList(list);
                    }
                }
            }
        } catch (final SQLException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    protected DataSource getDataSource() {
        return dataSource;
    }

}