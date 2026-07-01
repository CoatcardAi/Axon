package com.coatcard.axon.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class KeysExhaustedException extends RuntimeException {
    public KeysExhaustedException(String message) {
        super(message);
    }
}
