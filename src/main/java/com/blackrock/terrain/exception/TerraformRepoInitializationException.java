package com.blackrock.terrain.exception;

public class TerraformRepoInitializationException extends RuntimeException {

    public TerraformRepoInitializationException(String message) {
        super(message);
    }

    public TerraformRepoInitializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public TerraformRepoInitializationException(Throwable cause) {
        super(cause);
    }
}
