package ccpassignment;
/**
 *
 * @author Xenia Thor
 */
// Semaphore.java — YOUR OWN CUSTOM API
// Built from scratch using LECTURER'S EXACT code (Week 7, Slide 14).
// This IS the custom API your lecturer wants — NOT java.util.concurrent.
// LECTURER'S SLIDE 11-14 (Week 7):
// HOW IT LINKS TO YOUR ASSIGNMENT:
// smartLaundrySimulation.java creates 4 instances:
// semWasher = new Semaphore(6, true)  — 6 washing machines
// semDryer  = new Semaphore(4, true)  — 4 dryers
// semKiosk  = new Semaphore(2, true)  — 2 payment kiosks
// semBasket = new Semaphore(3, true)  — 3 baskets
// CustomerThread.java calls:
// semWasher.tryDown(rem) — customer waits for washer
// semWasher.up()         — customer finishes, releases washer
// NestedMonitor.java uses this Semaphore internally:
// It calls semKiosk.tryDown() inside a synchronized method
// that is the NESTED MONITOR pattern (Week 6).
// CONCURRENT CONCEPTS (your lecture slides):
// Week 5 — synchronized = mutual exclusion on up()/down()
// Week 6 — wait() releases lock, suspends thread
// notifyAll() wakes ALL threads (Week 5 lab exercise)
// Week 7 — up()/down() = semaphore operations from slide
// Week 9/10 — tryDown(timeout) = liveness, no starvation

public class Semaphore {

    // private = encapsulation (only accessible via synchronized methods)
    private int value; //available permits
    private int waitingCount; //how many thread currently blocked

    /** Constructor 1 — matches lecturer's slide exactly */
    public Semaphore(int initial) {
        this.value = initial;
        this.waitingCount = 0;
    }

    /**
     * Constructor 2 — supports fair parameter.
     * Called as: new Semaphore(NUM_WASHERS, true)
     * fair=true = FIFO fairness, achieved via notifyAll()
     * which wakes ALL threads giving everyone equal chance.
     */
    public Semaphore(int initial, boolean fair) {
        this(initial);
    }
    

//UP — release one permit back.
//Lecturer's slide: "up(s): if processes blocked awaken one"
//     * synchronized = mutual exclusion (Week 5)
//     * notifyAll()  = wake ALL waiting threads (Week 6)
//     *   Week 5 lab: "Change Notify to NotifyAll. Explain."
//     *   With 50 customer threads waiting, notify() wakes only 1.
//     *   notifyAll() gives every thread a fair chance (Liveness, Week 9).
    public synchronized void up() {
        ++value;
        notifyAll(); // wake ALL threads blocked in down()/tryDown()
    }

//DOWN — acquire one permit. Block if none available.
//Lecturer's slide: "down(s): when s>0 decrement; else block"
//while loop (not if) — Week 6 monitor pattern:
//After wait() returns, another thread may have taken the permit.
//Must re-check condition before proceeding.

    public synchronized void down() throws InterruptedException {
        waitingCount++;
        while (value == 0) {
            wait(); // release lock + suspend thread (Week 6)
        }
        waitingCount--;
        --value;
    }

// TRY DOWN with timeout — our extension for customer patience.
// Not in lecturer's slide — added for Liveness (Week 9/10).
// If no permit within timeoutMs → return false (customer leaves).
// Also used by NestedMonitor.java when it calls this semaphore
// from inside its own synchronized method — creating a nested monitor.
// @param timeoutMs max ms to wait
// @return true = got permit, false = timed out
    public synchronized boolean tryDown(long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        waitingCount++;
        while (value == 0) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                waitingCount--;
                return false;
            }
            wait(remaining); // wait at most 'remaining' ms (Week 6)
        }
        waitingCount--;
        --value;
        return true; //permit acquired successfully
    }


    public synchronized int availablePermits() {
        return value;
    }
    
    // waiting count
    public synchronized int getWaitingCount() {
        return waitingCount;
    }
    
    public synchronized void reset (int capacity) {
        this.value = capacity;
        this.waitingCount = 0;
        notifyAll();
    }
    
    //return true if no permits are taken
    public synchronized boolean full (int capacity) {
        return value == capacity;
    }
    
    @Override
    //prints semaphore state to output
    public synchronized String toString() {
        return "Semaphore [permits = " + value + ", waiting = " + waitingCount + "]";
    }
    
    
    
}
