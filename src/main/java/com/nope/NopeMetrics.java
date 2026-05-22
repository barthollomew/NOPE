package com.nope;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

public final class NopeMetrics implements NopeMetricsMXBean {
    private final LoadShedder shedder;
    private final NopeSemaphore semaphore;
    private final ConcurrencyLimiter limiter;
    private final SojournTracker sojournTracker;
    private final CoDelController codel;

    public NopeMetrics(LoadShedder shedder,
                       NopeSemaphore semaphore,
                       ConcurrencyLimiter limiter,
                       SojournTracker sojournTracker,
                       CoDelController codel) {
        this.shedder = shedder;
        this.semaphore = semaphore;
        this.limiter = limiter;
        this.sojournTracker = sojournTracker;
        this.codel = codel;
    }

    public void register() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.nope:type=NopeMetrics");
            if (!mbs.isRegistered(name)) {
                mbs.registerMBean(this, name);
            }
        } catch (Exception e) {
            // JMX registration failure is non-fatal
        }
    }

    public void unregister() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("com.nope:type=NopeMetrics");
            if (mbs.isRegistered(name)) {
                mbs.unregisterMBean(name);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    @Override public long getAccepted() { return shedder.accepted(); }
    @Override public long getRejected() { return shedder.rejected(); }
    @Override public long getShedBackground() { return shedder.shedBackground(); }
    @Override public long getShedStandard() { return shedder.shedStandard(); }
    @Override public long getShedCritical() { return shedder.shedCritical(); }
    @Override public int getCurrentLimit() { return limiter.currentLimit(); }
    @Override public int getAvailablePermits() { return semaphore.available(); }
    @Override public double getEmaSojournMillis() { return sojournTracker.emaSojournNanos() / 1_000_000.0; }
    @Override public boolean isCoDelDropping() { return codel.isDropping(); }
    @Override public int getCoDelDropCount() { return codel.dropCount(); }
}
