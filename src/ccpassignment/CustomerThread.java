package ccpassignment;

import javax.swing.*;
import java.awt.*;
import java.util.Random;


/**
 *
 * @author Xenia Thor
 */
public class CustomerThread extends Thread {

    // Customer visible state
    public volatile SmartLaundrySimulation.CustomerState customerState =
        SmartLaundrySimulation.CustomerState.ARRIVED;

    //unique customerID
    public final int id;
    private final long startMs;
    private final long patienceMs; // total patience window (20-40 s)
    private long totalWaitMs; // accumulated wait time
    private final Random rng = new Random();

    public CustomerThread(int id) {
        super("Customer " + id);
        setDaemon(true);
        this.id = id;
        this.startMs = System.currentTimeMillis();
        //wash= 4-6s  dry= 3-5s  pay= 1-2s
        int patienceSeconds = rng.nextInt(21)+20;
        this.patienceMs = patienceSeconds*1000L;
        
        //customer arrive message (print out in gui and output)
        SmartLaundrySimulation.messageTerminal (
        "Customer " + id + " ARIVED (patience = " + patienceSeconds + "s)");
    }

    //  MAIN (using deadline concept)
    @Override
    public void run() {
        // STEP 1 — Basket (rank 1)
        int basketSlot = acquireBasket();
        if (basketSlot < 0) 
            return;  // left without getting basket

        // STEP 2 — Washer (rank 2)
        boolean washed = doWashing();
        if (!washed) {
            releaseBasket(basketSlot);
//            recordDeparture("gave up at washer");
            return;
        }

        // STEP 3 — Dryer (rank 3)
        boolean dried = doDrying();
        if (!dried) {
            releaseBasket(basketSlot);
            return;
        }

        // Return basket before payment (rank drops from 3 back to none)
        releaseBasket(basketSlot);
        SmartLaundrySimulation.messageTerminal(
            "Customer  " + id + " have return the basket");

        // STEP 4 — Payment Kiosk (rank 4)
        boolean paid = doPayment();
        if (!paid) return;

        // DONE 
        customerState = SmartLaundrySimulation.CustomerState.DONE;
        long elapsed = System.currentTimeMillis() - startMs;
        //mechanism 3 - AtomicInteger: lock-free increment
        SmartLaundrySimulation.statsLock.lock();
        try {
            SmartLaundrySimulation.statDone++;
            SmartLaundrySimulation.statTotalMs += elapsed;
        } finally { SmartLaundrySimulation.statsLock.unlock(); }
        
        SmartLaundrySimulation.messageTerminal(
            "Customer " + id + " DONE  total=" + (elapsed/1000) + "s");
    }

    //backet, claim for random free slot, return slot
    private int acquireBasket() {
        customerState = SmartLaundrySimulation.CustomerState.BASKET;
        long rem = remaining();
        if (rem <= 0) {
            tiredOfWaiting("no patience on arrival");
            return -1;
        }
 
        SmartLaundrySimulation.messageTerminal(
            "Customer " + id + " waiting for basket...");
        long t0 = System.currentTimeMillis();
        boolean ok;
        try {
            // OUR CUSTOM SEMAPHORE — down(s) with patience timeout
            // From Semaphore.java we built (Week 7 Slide 14)
            ok = SmartLaundrySimulation.semBasket.tryDown(rem);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tiredOfWaiting("interrupted at basket");
            return -1;
        }
        totalWaitMs += System.currentTimeMillis() - t0;
 
        if (!ok) {
            // Waited too long for basket and leave
            tiredOfWaiting("waited too long for basket");
            return -1;
        }
 
        // synchronized = mutual exclusion on basketBusy[] (Week 5)
        // Without this: two threads could claim same basket = interference
        int slot = claimRandomSlot(
            SmartLaundrySimulation.basketBusy,
            SmartLaundrySimulation.basketOwner);
        SmartLaundrySimulation.messageTerminal(
            "Customer " + id + " get basket" + (slot + 1));
        return slot;
    }

    private void releaseBasket(int slot) {
        if (slot >= 0) {
            synchronized (SmartLaundrySimulation.machineLock) {
                SmartLaundrySimulation.basketBusy[slot]  = false;
                SmartLaundrySimulation.basketOwner[slot] = 0;
            }
            // OUR CUSTOM SEMAPHORE — up(s) releases permit (Week 7)
            SmartLaundrySimulation.semBasket.up();
        }
    }

    //  STEP 2: WASHING  (10% failure → retry up to 3×)
    private boolean doWashing() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            customerState = SmartLaundrySimulation.CustomerState.WASHING;
            long rem = remaining();
            if (rem <= 0) {
                tiredOfWaiting("ran out of patience before washer");
                return false;
            }
 
            SmartLaundrySimulation.messageTerminal
            (
                "Customer " + id +
                " waiting for washer (attempt " + attempt + ")...");
            long t0 = System.currentTimeMillis();
            boolean ok;
            try {
                // OUR CUSTOM SEMAPHORE down(s) — Week 7
                ok = SmartLaundrySimulation.semWasher.tryDown(rem);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                tiredOfWaiting("interrupted waiting for washer");
                return false;
            }
            totalWaitMs += System.currentTimeMillis() - t0;
 
            if (!ok) {
                // Waited too long for washer → leave
                tiredOfWaiting("wait too long for washer");
                return false;
            }
 
            // Claim random slot — synchronized = mutual exclusion (Week 5)
            int slot = claimRandomSlot(
                SmartLaundrySimulation.washerBusy,
                SmartLaundrySimulation.washerOwner);
 
            // AtomicInteger — lock-free atomic counter update (Week 3)
            int cur = SmartLaundrySimulation.activeWashers.incrementAndGet();
            SmartLaundrySimulation.updateMax(
                SmartLaundrySimulation.peakWashers, cur);
 
            int washMs = 4_000 + rng.nextInt(2_001); // 4-6 seconds
            SmartLaundrySimulation.messageTerminal(
                "Customer " + id + " WASHING now" + (slot + 1) +
                " (" + washMs / 1000 + "s, attempt " + attempt + ")");
 
            try {
                Thread.sleep(washMs);
            } catch (InterruptedException e) {
                freeSlot(SmartLaundrySimulation.washerBusy,
                         SmartLaundrySimulation.washerOwner, slot);
                SmartLaundrySimulation.semWasher.up();
                SmartLaundrySimulation.activeWashers.decrementAndGet();
                Thread.currentThread().interrupt();
                tiredOfWaiting("interrupted during washing");
                return false;
            }
 
            // Release washer slot
            freeSlot(SmartLaundrySimulation.washerBusy,
                     SmartLaundrySimulation.washerOwner, slot);
            SmartLaundrySimulation.semWasher.up(); // OUR custom up(s)
            SmartLaundrySimulation.activeWashers.decrementAndGet();
 
            // 10% machine failure — retry loop
            if (rng.nextDouble() < 0.10) {
                SmartLaundrySimulation.messageTerminal(
                    "Customer " + id + " - W" + (slot + 1) +
                    " FAILED mid-cycle! Retrying in 2s...");
                try { Thread.sleep(2_000); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    tiredOfWaiting("interrupted after washer failure");
                    return false;
                }
                continue; // retry
            }
 
            SmartLaundrySimulation.messageTerminal(
                "Customer " + id + " finished WASHING");
            return true;
        }
        // All 3 retries exhausted
        tiredOfWaiting("washer kept failing");
        return false;
    }

    // DRYING
    private boolean doDrying() {
        customerState = SmartLaundrySimulation.CustomerState.DRYING;
        long rem = remaining();
        if (rem <= 0) {
            tiredOfWaiting("ran out of patience before dryer");
            return false;
        }
 
        SmartLaundrySimulation.messageTerminal(
            "Customer " + id + " waiting for dryer...");
        long t0 = System.currentTimeMillis();
        boolean ok;
        try {
            // OUR CUSTOM SEMAPHORE down(s) — Week 7
            ok = SmartLaundrySimulation.semDryer.tryDown(rem);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            tiredOfWaiting("interrupted waiting for dryer");
            return false;
        }
        totalWaitMs += System.currentTimeMillis() - t0;
 
        if (!ok) {
            // Waited too long for dryer → leave
            tiredOfWaiting("wait too long for dryer");
            return false;
        }
 
        int slot = claimRandomSlot(
            SmartLaundrySimulation.dryerBusy,
            SmartLaundrySimulation.dryerOwner);
 
        int cur = SmartLaundrySimulation.activeDryers.incrementAndGet();
        SmartLaundrySimulation.updateMax(
            SmartLaundrySimulation.peakDryers, cur);
 
        int dryMs = 3_000 + rng.nextInt(2_001); // 3-5 seconds
        SmartLaundrySimulation.messageTerminal(
            "Customer " + id + " DRYING now" + (slot + 1) +
            " (" + dryMs / 1000 + "s)");
 
        try {
            Thread.sleep(dryMs);
        } catch (InterruptedException e) {
            freeSlot(SmartLaundrySimulation.dryerBusy,
                     SmartLaundrySimulation.dryerOwner, slot);
            SmartLaundrySimulation.semDryer.up();
            SmartLaundrySimulation.activeDryers.decrementAndGet();
            Thread.currentThread().interrupt();
            tiredOfWaiting("interrupted during drying");
            return false;
        }
 
        freeSlot(SmartLaundrySimulation.dryerBusy,
                 SmartLaundrySimulation.dryerOwner, slot);
        SmartLaundrySimulation.semDryer.up(); // OUR custom up(s)
        SmartLaundrySimulation.activeDryers.decrementAndGet();
        SmartLaundrySimulation.messageTerminal(
            "Customer " + id + " finished DRYING");
        return true;
    }

    // PAYMENT  (10% kiosk failure; bonus: system down)
    private boolean doPayment() {
        customerState = SmartLaundrySimulation.CustomerState.PAYMENT;
        long rem = remaining();
        if (rem <= 0) {
            tiredOfWaiting("ran out of patience before payment");
            return false;
        }
 
        // NestedMonitor: track this customer joined queue
        SmartLaundrySimulation.paymentMonitor.customerArrived(id);
 
        // NestedMonitor: wait if bonus system is down
        // Uses wait()/notifyAll() inside NestedMonitor (Week 6)
        boolean sysOk;
        try {
            sysOk = SmartLaundrySimulation.paymentMonitor
                        .waitIfSystemDown(id, rem);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SmartLaundrySimulation.paymentMonitor.customerLeft(id);
            tiredOfWaiting("interrupted waiting for system restore");
            return false;
        }
        if (!sysOk) {
            SmartLaundrySimulation.paymentMonitor.customerLeft(id);
            tiredOfWaiting("payment system still down");
            return false;
        }
 
        // OUR CUSTOM SEMAPHORE — down(s) for kiosk (Week 7)
        // Called AFTER NestedMonitor releases — avoids nested deadlock
        rem = remaining();
        if (rem <= 0) {
            SmartLaundrySimulation.paymentMonitor.customerLeft(id);
            tiredOfWaiting("ran out of patience in payment queue");
            return false;
        }
 
        long t0 = System.currentTimeMillis();
        boolean ok;
        try {
            ok = SmartLaundrySimulation.semKiosk.tryDown(rem);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SmartLaundrySimulation.paymentMonitor.customerLeft(id);
            tiredOfWaiting("interrupted waiting for kiosk");
            return false;
        }
        totalWaitMs += System.currentTimeMillis() - t0;
 
        if (!ok) {
            SmartLaundrySimulation.paymentMonitor.customerLeft(id);
            tiredOfWaiting("waited too long for payment kiosk");
            return false;
        }
 
        int slot = claimRandomSlot(
            SmartLaundrySimulation.kioskBusy,
            SmartLaundrySimulation.kioskOwner);
        int payMs = 1_000 + rng.nextInt(1_001); // 1-2 seconds
 
        // 10% kiosk failure — retry after 2 seconds
        if (rng.nextDouble() < 0.10) {
            SmartLaundrySimulation.messageTerminal(
                "Customer " + id + " - ATM" + (slot + 1) +
                " FAILED! Retrying in 2s..."
            );
            try { 
                Thread.sleep(2_000); 
            } catch (InterruptedException e) {
                freeSlot(SmartLaundrySimulation.kioskBusy,
                         SmartLaundrySimulation.kioskOwner, slot);
                SmartLaundrySimulation.semKiosk.up();
                SmartLaundrySimulation.paymentMonitor.customerLeft(id);
                Thread.currentThread().interrupt();
                tiredOfWaiting("interrupted during kiosk retry");
                return false;
            }
        }
 
        SmartLaundrySimulation.messageTerminal(
            "Customer " + id + " PAYING at ATM" + (slot + 1) +
            " (" + payMs / 1000 + "s)");
 
        try { Thread.sleep(payMs); }
        catch (InterruptedException e) {
            freeSlot(SmartLaundrySimulation.kioskBusy,
                     SmartLaundrySimulation.kioskOwner, slot);
            SmartLaundrySimulation.semKiosk.up();
            SmartLaundrySimulation.paymentMonitor.customerLeft(id);
            Thread.currentThread().interrupt();
            tiredOfWaiting("interrupted during payment");
            return false;
        }
 
        // Release kiosk and notify NestedMonitor
        freeSlot(SmartLaundrySimulation.kioskBusy,
                 SmartLaundrySimulation.kioskOwner, slot);
        SmartLaundrySimulation.semKiosk.up(); // OUR custom up(s)
        SmartLaundrySimulation.paymentMonitor.customerLeft(id);
 
        SmartLaundrySimulation.messageTerminal(
            "Customer " + id + " PAID at ATM" + (slot + 1));
 
        // Play beep sound on successful payment
        SmartLaundrySimulation.playBeep();
        return true;
    }

// Thread-safe: called inside synchronized(machineLock).
    private void tiredOfWaiting(String reason) {
        customerState = SmartLaundrySimulation.CustomerState.LEFT;
        long elapsed = System.currentTimeMillis() - startMs;
 
        // Mutual exclusion on statistics (Week 5)
        SmartLaundrySimulation.statsLock.lock();
        try {
            SmartLaundrySimulation.statLeft++;
            SmartLaundrySimulation.statLeftMs += elapsed;
        } finally {
            SmartLaundrySimulation.statsLock.unlock();
        }
 
        // Print the departure message with customer ID
        SmartLaundrySimulation.messageTerminal(
            "Customer " + id + " tired of waiting. Bye!" +
            "  [" + reason + ", waited =" + totalWaitMs / 1000 + "s]");
    }
 
    /**
     * Claims a RANDOM free slot from the array.
     *
     * synchronized(machineLock) = MUTUAL EXCLUSION (Week 5)
     * Without this: two threads could BOTH read busy[i]=false
     * and claim the same slot = INTERFERENCE (Week 3 race condition)
     *
     * Random slot: ensures W3 lights up before W1, D4 before D2 etc.
     */
    private int claimRandomSlot(boolean[] busy, int[] owner) {
        synchronized (SmartLaundrySimulation.machineLock) {
            int[] free = new int[busy.length];
            int   cnt  = 0;
            for (int i = 0; i < busy.length; i++) {
                if (!busy[i]) free[cnt++] = i;
            }
            if (cnt == 0) {
                // Semaphore guarantees a slot is free — defensive fallback
                busy[0] = true; owner[0] = id; return 0;
            }
            int pick = free[rng.nextInt(cnt)]; // random selection
            busy[pick] = true; owner[pick] = id;
            return pick;
        }
    }
 
    private void freeSlot(boolean[] busy, int[] owner, int slot) {
        if (slot >= 0) {
            synchronized (SmartLaundrySimulation.machineLock) {
                busy[slot] = false; owner[slot] = 0;
            }
        }
    }
 
    //Remaining patience in milliseconds
    private long remaining() {
        return (startMs + patienceMs) - System.currentTimeMillis();
    }
}
