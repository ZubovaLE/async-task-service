package com.zubova.asynctaskservice.exception;

public class InvalidTaskMessageException extends RuntimeException {

    public InvalidTaskMessageException(String message) {
        super(message);
    }

}