package com.stog.backend.plan;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

final class SchemaScopedDataSource extends AbstractDataSource {
    private final DataSource delegate;
    private final String schema;

    SchemaScopedDataSource(DataSource delegate, String schema) {
        this.delegate = delegate;
        this.schema = schema;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return configure(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return configure(delegate.getConnection(username, password));
    }

    private Connection configure(Connection connection) throws SQLException {
        connection.createStatement().execute("SET search_path TO " + schema + ", public");
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
                if ("close".equals(method.getName())) {
                    connection.createStatement().execute("SET search_path TO public");
                    connection.close();
                    return null;
                }
                try {
                    return method.invoke(connection, arguments);
                } catch (InvocationTargetException error) {
                    throw error.getCause();
                }
            }
        );
    }
}
