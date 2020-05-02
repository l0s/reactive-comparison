package com.macasaet.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

import org.h2.tools.Server;
import org.h2.tools.Shell;

public class DatabaseServer {

    private Server server;

    public void start() {
        // FIXME parameterise or use random file name
        // TODO use relative path (e.g. target directory)
        final var dbDirectory = new File("/tmp/h2");
        assertTrue(delete(dbDirectory), "unable to proactively delete db directory: " + dbDirectory);
        assertTrue(dbDirectory.mkdir(), "unable to create directory: " + dbDirectory);
        dbDirectory.deleteOnExit();
        try {
            this.server = Server.createPgServer("-pgPort", "5435", "-baseDir", dbDirectory.getAbsolutePath(),
                    "-ifNotExists");
            server.start();

            try (var schema = getClass().getResourceAsStream("/schema.sql")) {
                final var shell = new Shell();
                shell.setIn(schema);
                shell.runTool("-url", "jdbc:postgresql://localhost:5435/db");
            } catch (final IOException ioe) {
                ioe.printStackTrace();
                throw new RuntimeException(ioe.getMessage(), ioe);
            }
        } catch (final SQLException e) {
            e.printStackTrace();
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop();
            assertFalse(server.isRunning(true));
        }
    }

    protected boolean delete(final File file) {
        if (!file.exists()) {
            return true;
        }
        var success = true;
        if (file.isDirectory()) {
            for (final var subFile : file.listFiles()) {
                success &= delete(subFile);
                if (!success) {
                    System.err.println("-- unable  to delete: " + subFile);
                }
            }
        }
        return success && file.delete();
    }

    public static final void main( final String... arguments ) {
        final var instance = new DatabaseServer();
        instance.start();
    }
}