package com.english9x.grading.db;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Connection of the grader login role ({@code e9x_grader_login}). The password has no default: the application refuses to start without it. */
@ConfigurationProperties("e9x.grading.datasource")
record GraderDataSourceProperties(String url, String username, String password, @DefaultValue("4") int maximumPoolSize) {

    @Override
    public String toString() {
        return "GraderDataSourceProperties[url=" + url + ", username=" + username + "]";
    }
}
