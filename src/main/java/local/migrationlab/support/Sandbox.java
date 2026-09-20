package local.migrationlab.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class Sandbox {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,62}");
    private final String baseUrl;

    public Sandbox(@Value("${migration-lab.database-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Connection connection(String schema) {
        if (!IDENTIFIER.matcher(schema).matches()) {
            throw new IllegalArgumentException("Illegal schema identifier");
        }
        try {
            Connection connection = DriverManager.getConnection(baseUrl, "sa", "");
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to open sandbox connection", e);
        }
    }

    public String urlFor(String schema) {
        int optionIndex = baseUrl.indexOf(';');
        String prefix = optionIndex < 0 ? baseUrl : baseUrl.substring(0, optionIndex);
        String options = optionIndex < 0 ? "" : baseUrl.substring(optionIndex);
        return prefix + ";SCHEMA=" + schema.toUpperCase(java.util.Locale.ROOT) + options;
    }

    public Connection bootstrapConnection() {
        try {
            Connection connection = DriverManager.getConnection(baseUrl, "sa", "");
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to open bootstrap connection", e);
        }
    }

    public static void identifier(String value) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Illegal SQL identifier: " + value);
        }
    }
}
