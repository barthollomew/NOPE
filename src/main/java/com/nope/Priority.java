package com.nope;

public enum Priority {
    CRITICAL(0),
    STANDARD(1),
    BACKGROUND(2);

    final int level;

    Priority(int level) {
        this.level = level;
    }

    public boolean isHigherThan(Priority other) {
        return this.level < other.level;
    }
}
