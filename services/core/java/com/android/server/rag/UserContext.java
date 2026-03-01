package com.android.server.rag;

import io.objectbox.annotation.Entity;
import io.objectbox.annotation.Id;

/**
 * The brain — persistent facts JarvisOS learns about the user.
 * Single record per device, updated over time.
 */
@Entity
public class UserContext {
    @Id
    public long id;

    public String preferredName;
    public String interests;        // comma-separated topics user frequently asks about
    public String frequentFolders;  // comma-separated folder paths accessed most
    public long lastActiveAt;
    public String deviceLocale;

    public UserContext() {}

    public UserContext(String deviceLocale) {
        this.deviceLocale = deviceLocale;
        this.lastActiveAt = System.currentTimeMillis();
    }
}
