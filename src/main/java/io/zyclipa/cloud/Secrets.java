package io.zyclipa.cloud;

import java.io.IOException;

/** Somewhere safe to keep one secret (a refresh token or a key password). */
public interface Secrets {
    void save(String secret) throws IOException;
    /** The secret, or null when there is none (or it can't be read). */
    String load();
    void clear() throws IOException;
}
