package com.cloudbees.jenkins.plugins.bitbucket;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

public class LogFormatter extends SimpleFormatter {

    @Override
    public String format(LogRecord logRecord) {
        String formattedRecord = super.format(logRecord);

        StringBuilder sb = new StringBuilder();

        // Prepend a timestamp
        Instant instant = Instant.ofEpochMilli(logRecord.getMillis());
        ZonedDateTime timestamp = ZonedDateTime.ofInstant(instant, ZoneId.systemDefault());
        sb.append("[" + timestamp.toLocalTime().toString() + "]");
        sb.append(' ');
        sb.append(formattedRecord);

        return sb.toString();
    }
}
