package com.english9x.content.media.api;

import java.util.UUID;

/**
 * An extra way to be allowed a link to a file, besides being the exam's author or reviewer: a module that owns a reason to listen (a learner entitled to a
 * course that uses the file, P3.1) implements this. Any one policy answering {@code true} is enough; a policy runs as the person bound to the thread and answers
 * {@code false} when it cannot tell.
 */
public interface MediaAccessPolicy {

    boolean mayPlay(UUID mediaId);
}
