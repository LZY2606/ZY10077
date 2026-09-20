package local.migrationlab.domain;

import javax.sql.DataSource;
import java.sql.SQLException;
import local.migrationlab.support.SimulatedProcessExitException;
import org.springframework.stereotype.Component;

@Component
public class CrashInjector {
    private final DataSource dataSource;

    public CrashInjector(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void maybeCrash(String armedPoint, String exitMode, String faultPoint) {
        if (armedPoint == null || !armedPoint.equals(faultPoint)) {
            return;
        }
        if ("halt".equalsIgnoreCase(exitMode)) {
            try (java.sql.Connection connection = dataSource.getConnection();
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute("checkpoint");
            } catch (SQLException e) {
                throw new IllegalStateException("Unable to checkpoint before simulated process exit", e);
            }
            Runtime.getRuntime().halt(86);
        }
        throw new SimulatedProcessExitException(faultPoint);
    }
}
