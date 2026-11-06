package com.english9x.platform.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Connection of the worker login role. The password has no default: the worker refuses to start without it. */
@ConfigurationProperties("e9x.jobs.worker-datasource")
record WorkerDataSourceProperties(String url, String username, String password, @DefaultValue("4") int maximumPoolSize) {

    @Override
    public String toString() {
        return "WorkerDataSourceProperties[url=" + url + ", username=" + username + "]";
    }
}
