package com.nope;

public interface NopeMetricsMXBean {
    long getAccepted();
    long getRejected();
    long getShedBackground();
    long getShedStandard();
    long getShedCritical();
    int getCurrentLimit();
    int getAvailablePermits();
    double getEmaSojournMillis();
    boolean isCoDelDropping();
    int getCoDelDropCount();
}
