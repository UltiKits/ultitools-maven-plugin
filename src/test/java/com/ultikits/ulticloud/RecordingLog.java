package com.ultikits.ulticloud;

import org.apache.maven.plugin.logging.Log;

import java.util.ArrayList;
import java.util.List;

/** A Maven log that keeps every line, including exception text, for assertions. */
public final class RecordingLog implements Log {

    private final List<String> lines = new ArrayList<String>();

    public List<String> lines() {
        return lines;
    }

    public String all() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private void add(String level, CharSequence content, Throwable error) {
        lines.add(level + " " + content + (error == null ? "" : " " + error));
    }

    public boolean isDebugEnabled() { return true; }
    public void debug(CharSequence content) { add("DEBUG", content, null); }
    public void debug(CharSequence content, Throwable error) { add("DEBUG", content, error); }
    public void debug(Throwable error) { add("DEBUG", "", error); }
    public boolean isInfoEnabled() { return true; }
    public void info(CharSequence content) { add("INFO", content, null); }
    public void info(CharSequence content, Throwable error) { add("INFO", content, error); }
    public void info(Throwable error) { add("INFO", "", error); }
    public boolean isWarnEnabled() { return true; }
    public void warn(CharSequence content) { add("WARN", content, null); }
    public void warn(CharSequence content, Throwable error) { add("WARN", content, error); }
    public void warn(Throwable error) { add("WARN", "", error); }
    public boolean isErrorEnabled() { return true; }
    public void error(CharSequence content) { add("ERROR", content, null); }
    public void error(CharSequence content, Throwable error) { add("ERROR", content, error); }
    public void error(Throwable error) { add("ERROR", "", error); }
}
