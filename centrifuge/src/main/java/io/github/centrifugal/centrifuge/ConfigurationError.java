package io.github.centrifugal.centrifuge;

public class ConfigurationError extends Exception {
    ConfigurationError(Exception error) {
        super(error);
    }
}
