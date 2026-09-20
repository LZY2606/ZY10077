package local.migrationlab.support;

public class SimulatedProcessExitException extends RuntimeException {
    public SimulatedProcessExitException(String faultPoint) {
        super("Simulated process exit at " + faultPoint);
    }
}
