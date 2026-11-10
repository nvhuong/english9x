package com.english9x.content.media.api;

/** The staging area could not be reached or does not have the file (storage off, store down, key missing). Retrying later may help. */
public class StagedFilesException extends RuntimeException {

    public StagedFilesException(String message, Throwable cause) {
        super(message, cause);
    }
}
