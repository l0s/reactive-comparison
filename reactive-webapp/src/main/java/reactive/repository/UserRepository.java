package reactive.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;

import domain.User;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Repository
public class UserRepository {

    private static final String selectAllTemplate = "SELECT id, name FROM \"User\" LIMIT ? OFFSET ?;";
    private static final String selectByIdTemplate = "SELECT id, name FROM \"User\" WHERE id=?;";
    private static final String insertionTemplate = "INSERT INTO \"User\"( id, name ) values( ?, ? );";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    /*
     * Using direct JDBC for demonstration purposes. In practice, a reactive
     * JDBC framework would be used.
     */
    private final DataSource dataSource;
    private final Scheduler scheduler;

    @Autowired
    public UserRepository(final DataSource dataSource, @Qualifier("databaseScheduler") final Scheduler scheduler) {
        Objects.requireNonNull(dataSource);
        Objects.requireNonNull(scheduler);
        this.dataSource = dataSource;
        this.scheduler = scheduler;
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
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public Mono<User> findById(final UUID id) {
        final Mono<User> mono = Mono.create(sink -> {
            try {
                try (var connection = getDataSource().getConnection()) {
                    try (var statement = connection.prepareStatement(selectByIdTemplate, ResultSet.TYPE_FORWARD_ONLY,
                            ResultSet.CONCUR_READ_ONLY)) {
                        statement.setObject(1, id);
                        try (var resultSet = statement.executeQuery()) {
                            var hasFirstRow = resultSet.next();
                            if (!hasFirstRow) {
                                logger.error("No user found with id: {}", id);
                                sink.success();
                                return;
                            }
                            final var foundId = UUID.fromString(resultSet.getString(1));
                            final var name = resultSet.getString(2);
                            final var retval = new User(foundId, name);
                            if (resultSet.next()) {
                                sink.error(new IllegalStateException("Extra rows, pk constraint violated"));
                                return;
                            }
                            sink.success(retval);
                        }
                    }
                }
            } catch (final SQLException e) {
                logger.error("Error finding for id: " + id + ": " + e.getMessage(), e);
                sink.error(e);
            }
        });
        return mono.publishOn(getScheduler());
    }

    public Flux<User> findAll(final int pageNumber, final int pageSize) {
        final var offset = pageSize * pageNumber;
        final Flux<User> flux = Flux.create(sink -> {
            try (var connection = getDataSource().getConnection()) {
                try (var statement = connection.prepareStatement(selectAllTemplate, ResultSet.TYPE_FORWARD_ONLY,
                        ResultSet.CONCUR_READ_ONLY)) {
                    statement.setInt(1, pageSize);
                    statement.setInt(2, offset);
                    try (var resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            final var id = UUID.fromString(resultSet.getString(1));
                            final var name = resultSet.getString(2);
                            sink.next(new User(id, name));
                        }
                        sink.complete();
                    }
                }
            } catch (final SQLException se) {
                logger.error("Error finding page: " + pageNumber + " of size: " + pageSize + ": " + se.getMessage(),
                        se);
                sink.error(se);
            }
        });
        return flux/*.publishOn(getScheduler())*/; // FIXME executing on scheduler causes incorrect behaviour
    }

    protected DataSource getDataSource() {
        return dataSource;
    }

    protected Scheduler getScheduler() {
        return scheduler;
    }

}