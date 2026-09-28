package com.glowingfederal.worldeditoverdrive.integration;

/** Conservative live heap reservations, shared by capture, workers and replay.
 * Clipboard storage belongs to WorldEdit; it is borrowed, never copied wholesale.
 * Disk bytes are deliberately separate from these heap limits. */
final class PasteMemoryBudget {
    enum Kind { STATE, CAPTURE, ENTITY, PLANNING, COMMIT, HISTORY, WORKER }
    final long globalLimit, operationLimit;
    private long live, peak;
    PasteMemoryBudget(long globalLimit, long operationLimit) {
        if (globalLimit <= 0 || operationLimit <= 0) throw new IllegalArgumentException("memory budget");
        this.globalLimit = globalLimit; this.operationLimit = operationLimit;
    }
    Account account() { return new Account(); }
    synchronized long live() { return live; }
    synchronized long peak() { return peak; }
    final class Account {
        private final long[] kinds = new long[Kind.values().length];
        private long used, high;
        Ticket admitDescriptor() {
            // A waiting admission needs a small descriptor even when workers fill
            // the budget. Report it, including this explicit 16 KiB/owner tolerance;
            // every subsequent allocation must fit both ordinary limits.
            synchronized (PasteMemoryBudget.this) {
                long bytes = 16L << 10; used += bytes; live += bytes; kinds[Kind.STATE.ordinal()] += bytes;
                high = Math.max(high, used); peak = Math.max(peak, live); return new Ticket(this, Kind.STATE, bytes);
            }
        }
        Ticket acquire(Kind kind, long bytes) {
            if (bytes < 0 || bytes > operationLimit) throw new IllegalArgumentException("indivisible paste record exceeds working memory: " + bytes);
            synchronized (PasteMemoryBudget.this) {
                if (bytes > operationLimit - used || bytes > globalLimit - live) return null;
                used += bytes; live += bytes; kinds[kind.ordinal()] += bytes;
                high = Math.max(high, used); peak = Math.max(peak, live);
                return new Ticket(this, kind, bytes);
            }
        }
        long live() { synchronized (PasteMemoryBudget.this) { return used; } }
        long peak() { synchronized (PasteMemoryBudget.this) { return high; } }
        long bytes(Kind kind) { synchronized (PasteMemoryBudget.this) { return kinds[kind.ordinal()]; } }
        long limit() { return operationLimit; }
    }
    final class Ticket implements AutoCloseable {
        private final Account account;
        private Kind kind;
        private long bytes;
        Ticket(Account account, Kind kind, long bytes) { this.account = account; this.kind = kind; this.bytes = bytes; }
        long bytes() { synchronized (PasteMemoryBudget.this) { return bytes; } }
        Ticket split(Kind destination, long amount) {
            synchronized (PasteMemoryBudget.this) {
                if (amount < 0 || amount > bytes) throw new IllegalStateException("paste reservation underestimated");
                bytes -= amount; account.kinds[kind.ordinal()] -= amount; account.kinds[destination.ordinal()] += amount;
                return new Ticket(account, destination, amount);
            }
        }
        void move(Kind destination) {
            synchronized (PasteMemoryBudget.this) {
                account.kinds[kind.ordinal()] -= bytes; account.kinds[destination.ordinal()] += bytes; kind = destination;
            }
        }
        public void close() {
            synchronized (PasteMemoryBudget.this) {
                live -= bytes; account.used -= bytes; account.kinds[kind.ordinal()] -= bytes; bytes = 0;
            }
        }
    }
}
