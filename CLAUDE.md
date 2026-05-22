# NOPE Guidelines
- **Stack:** Java 21+, JMH Microbenchmarks, VarHandle, CoDel math.
- **Build:** `./gradlew clean build jmh`
- **Rules:** Zero happy-path memory allocations. Lock-free structures only. Decouple queues by priority.
