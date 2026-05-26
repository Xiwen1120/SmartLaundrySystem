package ccpassignment;

import javax.swing.*;
import java.awt.*;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

/**
 *
 * @author Xenia Thor
 */


public class CustomerThread extends Thread {

    // Customer visible state
    public volatile SmartLaundrySimulation.CustomerState CustomerState =
            SmartLaundrySimulation.CustomerState.ARRIVED;

    public final int id;
    private final long  startMs;
    private final long  patienceMs;    // total patience window (30-55 s)
    private       long  totalWaitMs;   // accumulated wait time
    private final Random rng = new Random();

    public CustomerThread(int id) {
        super("Customer-" + id);
        setDaemon(true);
        this.id          = id;
        this.startMs     = System.currentTimeMillis();
        this.patienceMs  = 30_000L + rng.nextInt(25_001); // 30-55 s
        SmartLaundrySimulation.log(
            "🚶 Customer-" + id + " ARRIVED  (patience=" +
            (patienceMs/1000) + "s)");
    }

    //  MAIN PIPELINE
    @Override
    public void run() {
        // STEP 1 — Basket (rank 1)
        int basketSlot = acquireBasket();
        if (basketSlot < 0) return;  // left without getting basket

        // STEP 2 — Washer (rank 2)
        boolean washed = doWashing();
        if (!washed) {
            releaseBasket(basketSlot);
            recordDeparture("gave up at washer");
            return;
        }

        // STEP 3 — Dryer (rank 3)
        boolean dried = doDrying();
        if (!dried) {
            releaseBasket(basketSlot);
            recordDeparture("gave up at dryer");
            return;
        }

        // Return basket before payment (rank drops from 3 back to none)
        releaseBasket(basketSlot);
        SmartLaundrySimulation.log("🧺 Customer-" + id + " returned basket");

        // STEP 4 — Payment Kiosk (rank 4)
        boolean paid = doPayment();
        if (!paid) return;

        // ── DONE ───────────────────────────────────────────────
        CustomerState = SmartLaundrySimulation.CustomerState.DONE;
        long elapsed = System.currentTimeMillis() - startMs;
        SmartLaundrySimulation.statsLock.lock();
        try {
            SmartLaundrySimulation.statDone++;
            SmartLaundrySimulation.statTotalMs += elapsed;
        } finally { SmartLaundrySimulation.statsLock.unlock(); }
        
        SmartLaundrySimulation.log(
            "✅ Customer-" + id + " DONE  total=" + (elapsed/1000) + "s");
    }

//    //  STEP 1: BASKET
//     * Acquire a basket using Semaphore.tryAcquire(patience).
//     * Then claim a RANDOM free slot in basketBusy[] for the GUI.
//     * Returns slot index (0-2), or -1 if timed out / interrupted.
//     */
    private int acquireBasket() {
        CustomerState = SmartLaundrySimulation.CustomerState.BASKET;
        long rem = remaining();
        if (rem <= 0) { recordDeparture("no patience on arrival"); return -1; }

        SmartLaundrySimulation.log("🧺 Customer-" + id + " waiting for basket...");
        long t0 = System.currentTimeMillis();
        boolean ok;
        try {
            ok = SmartLaundrySimulation.semBasket.tryAcquire(rem, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordDeparture("interrupted at basket");
            return -1;
        }
        totalWaitMs += System.currentTimeMillis() - t0;

        if (!ok) { recordDeparture("basket timeout"); return -1; }

        // Claim a RANDOM free slot
        int slot = claimRandomSlot(
            SmartLaundrySimulation.basketBusy,
            SmartLaundrySimulation.basketOwner);

        SmartLaundrySimulation.log("🧺 Customer-" + id + " got basket BK" + (slot+1));
        return slot;
    }

    private void releaseBasket(int slot) {
        if (slot >= 0) {
            synchronized (SmartLaundrySimulation.machineLock) {
                SmartLaundrySimulation.basketBusy[slot]  = false;
                SmartLaundrySimulation.basketOwner[slot] = 0;
            }
            SmartLaundrySimulation.semBasket.release();
        }
    }

    // ═════════════════════════════════════════════════════════
    //  STEP 2: WASHING  (10% failure → retry up to 3×)
    // ═════════════════════════════════════════════════════════
    private boolean doWashing() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            CustomerState = SmartLaundrySimulation.CustomerState.WASHING;
            long rem = remaining();
            if (rem <= 0) return false;

            SmartLaundrySimulation.log(
                "🌀 Customer-" + id + " waiting for washer (attempt " + attempt + ")...");
            long t0 = System.currentTimeMillis();
            boolean ok;
            try {
                ok = SmartLaundrySimulation.semWasher.tryAcquire(rem, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            totalWaitMs += System.currentTimeMillis() - t0;
            if (!ok) return false;

            // Claim a RANDOM free washer slot
            int slot = claimRandomSlot(
                SmartLaundrySimulation.washerBusy,
                SmartLaundrySimulation.washerOwner);

            // Update atomic peak counter
            int cur = SmartLaundrySimulation.activeWashers.incrementAndGet();
            SmartLaundrySimulation.peakWashers.accumulateAndGet(cur, Math::max);

            int washMs = 4_000 + rng.nextInt(2_001); // 4-6 s
            SmartLaundrySimulation.log(
                "🌀 Customer-" + id + " WASHING on W" + (slot+1) +
                " (" + washMs/1000 + "s, attempt " + attempt + ")");

            try { Thread.sleep(washMs); }
            catch (InterruptedException e) {
                freeSlot(SmartLaundrySimulation.washerBusy,
                         SmartLaundrySimulation.washerOwner, slot);
                SmartLaundrySimulation.semWasher.release();
                SmartLaundrySimulation.activeWashers.decrementAndGet();
                Thread.currentThread().interrupt();
                return false;
            }

            // Release slot
            freeSlot(SmartLaundrySimulation.washerBusy,
                     SmartLaundrySimulation.washerOwner, slot);
            SmartLaundrySimulation.semWasher.release();
            SmartLaundrySimulation.activeWashers.decrementAndGet();

            // 10% machine failure
            if (rng.nextDouble() < 0.10) {
                SmartLaundrySimulation.log(
                    "⚠️ Customer-" + id + " — W" + (slot+1) +
                    " FAILED mid-cycle! Retrying in 2s...");
                try { Thread.sleep(2_000); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                continue; // retry
            }

            SmartLaundrySimulation.log("✔ Customer-" + id + " finished WASHING");
            return true;
        }
        return false; // all retries exhausted
    }

    // ═════════════════════════════════════════════════════════
    //  STEP 3: DRYING
    // ═════════════════════════════════════════════════════════
    private boolean doDrying() {
        CustomerState = SmartLaundrySimulation.CustomerState.DRYING;
        long rem = remaining();
        if (rem <= 0) return false;

        SmartLaundrySimulation.log("💨 Customer-" + id + " waiting for dryer...");
        long t0 = System.currentTimeMillis();
        boolean ok;
        try {
            ok = SmartLaundrySimulation.semDryer.tryAcquire(rem, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        totalWaitMs += System.currentTimeMillis() - t0;
        if (!ok) return false;

        // Claim a RANDOM free dryer slot
        int slot = claimRandomSlot(
            SmartLaundrySimulation.dryerBusy,
            SmartLaundrySimulation.dryerOwner);

        int cur = SmartLaundrySimulation.activeDryers.incrementAndGet();
        SmartLaundrySimulation.peakDryers.accumulateAndGet(cur, Math::max);

        int dryMs = 3_000 + rng.nextInt(2_001); // 3-5 s
        SmartLaundrySimulation.log(
            "💨 Customer-" + id + " DRYING on D" + (slot+1) + " (" + dryMs/1000 + "s)");

        try { Thread.sleep(dryMs); }
        catch (InterruptedException e) {
            freeSlot(SmartLaundrySimulation.dryerBusy,
                     SmartLaundrySimulation.dryerOwner, slot);
            SmartLaundrySimulation.semDryer.release();
            SmartLaundrySimulation.activeDryers.decrementAndGet();
            Thread.currentThread().interrupt();
            return false;
        }

        freeSlot(SmartLaundrySimulation.dryerBusy,
                 SmartLaundrySimulation.dryerOwner, slot);
        SmartLaundrySimulation.semDryer.release();
        SmartLaundrySimulation.activeDryers.decrementAndGet();
        SmartLaundrySimulation.log("✔ Customer-" + id + " finished DRYING");
        return true;
    }

    // ═════════════════════════════════════════════════════════
    //  STEP 4: PAYMENT  (10% kiosk failure; bonus: system down)
    // ═════════════════════════════════════════════════════════
    private boolean doPayment() {
        CustomerState = SmartLaundrySimulation.CustomerState.PAYMENT;
        SmartLaundrySimulation.payQueueSize.incrementAndGet();
        SmartLaundrySimulation.log(
            "💳 Customer-" + id + " joining payment queue (" +
            SmartLaundrySimulation.payQueueSize.get() + " waiting)");

        // BONUS: wait if payment system is down
        if (SmartLaundrySimulation.paymentDown) {
            SmartLaundrySimulation.log(
                "🔴 Customer-" + id + " — kiosks DOWN, waiting for owner...");
            long deadline = startMs + patienceMs;
            while (SmartLaundrySimulation.paymentDown &&
                   System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    SmartLaundrySimulation.payQueueSize.decrementAndGet();
                    recordDeparture("interrupted while payment down");
                    return false;
                }
            }
            if (SmartLaundrySimulation.paymentDown) {
                SmartLaundrySimulation.payQueueSize.decrementAndGet();
                recordDeparture("payment still down, gave up");
                return false;
            }
        }

        long rem = remaining();
        if (rem <= 0) {
            SmartLaundrySimulation.payQueueSize.decrementAndGet();
            recordDeparture("no patience for kiosk");
            return false;
        }

        long t0 = System.currentTimeMillis();
        boolean ok;
        try {
            ok = SmartLaundrySimulation.semKiosk.tryAcquire(rem, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SmartLaundrySimulation.payQueueSize.decrementAndGet();
            recordDeparture("interrupted at kiosk");
            return false;
        }
        totalWaitMs += System.currentTimeMillis() - t0;
        SmartLaundrySimulation.payQueueSize.decrementAndGet();
        if (!ok) { recordDeparture("kiosk timeout"); return false; }

        // Claim a RANDOM free kiosk slot
        int slot = claimRandomSlot(
            SmartLaundrySimulation.kioskBusy,
            SmartLaundrySimulation.kioskOwner);

        int payMs = 1_000 + rng.nextInt(1_001); // 1-2 s

        // 10% kiosk failure → retry after 2 s
        if (rng.nextDouble() < 0.10) {
            SmartLaundrySimulation.log(
                "⚠️ Customer-" + id + " — ATM" + (slot+1) + " FAILED! Retrying in 2s...");
            try { Thread.sleep(2_000); }
            catch (InterruptedException e) {
                freeSlot(SmartLaundrySimulation.kioskBusy,
                         SmartLaundrySimulation.kioskOwner, slot);
                SmartLaundrySimulation.semKiosk.release();
                Thread.currentThread().interrupt();
                recordDeparture("interrupted at kiosk retry");
                return false;
            }
        }

        SmartLaundrySimulation.log(
            "💳 Customer-" + id + " PAYING at ATM" + (slot+1) + " (" + payMs/1000 + "s)");

        try { Thread.sleep(payMs); }
        catch (InterruptedException e) {
            freeSlot(SmartLaundrySimulation.kioskBusy,
                     SmartLaundrySimulation.kioskOwner, slot);
            SmartLaundrySimulation.semKiosk.release();
            Thread.currentThread().interrupt();
            recordDeparture("interrupted during payment");
            return false;
        }

        freeSlot(SmartLaundrySimulation.kioskBusy,
                 SmartLaundrySimulation.kioskOwner, slot);
        SmartLaundrySimulation.semKiosk.release();
        SmartLaundrySimulation.log("✔ Customer-" + id + " PAID at ATM" + (slot+1));

        // 🔔 BEEP on successful payment
        SmartLaundrySimulation.playBeep();
        return true;
    }

    // ═════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════

    /**
     * Scans the busy array and collects ALL free slot indices,
     * then picks ONE at RANDOM.  This ensures that machines
     * light up randomly (W3 before W1, D4 before D1, etc.)
     * rather than always filling sequentially from the start.
     *
     * Thread-safe: called inside synchronized(machineLock).
     */
    private int claimRandomSlot(boolean[] busy, int[] owner) {
        // Collect free indices
        int[] freeSlots = new int[busy.length];
        int   freeCount = 0;
        synchronized (SmartLaundrySimulation.machineLock) {
            for (int i = 0; i < busy.length; i++) {
                if (!busy[i]) freeSlots[freeCount++] = i;
            }
            if (freeCount == 0) {
                // Semaphore already guarantees a slot is free —
                // fall back to 0 defensively
                busy[0]  = true;
                owner[0] = id;
                return 0;
            }
            // Pick a random one among free slots
            int pick = freeSlots[rng.nextInt(freeCount)];
            busy[pick]  = true;
            owner[pick] = id;
            return pick;
        }
    }

    /** Release a specific slot */
    private void freeSlot(boolean[] busy, int[] owner, int slot) {
        if (slot >= 0) {
            synchronized (SmartLaundrySimulation.machineLock) {
                busy[slot]  = false;
                owner[slot] = 0;
            }
        }
    }

    /** Remaining patience in milliseconds */
    private long remaining() {
        return (startMs + patienceMs) - System.currentTimeMillis();
    }

    /** Record departure — included in statistics */
    private void recordDeparture(String reason) {
        CustomerState = SmartLaundrySimulation.CustomerState.LEFT;
        long elapsed = System.currentTimeMillis() - startMs;
        SmartLaundrySimulation.statsLock.lock();
        try {
            SmartLaundrySimulation.statLeft++;
            SmartLaundrySimulation.statTotalMs += elapsed;
        } finally { SmartLaundrySimulation.statsLock.unlock(); }
        SmartLaundrySimulation.log(
            "🚶 Customer-" + id + " LEFT (" + reason +
            ", waited=" + totalWaitMs/1000 + "s)");
    }
}