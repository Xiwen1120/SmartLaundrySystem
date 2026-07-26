package ccpassignment;
/**
 *
 * @author Xenia Thor
 */
// create own API
public class Semaphore {
    // private = encapsulation (only accessible via synchronized methods)
    private int value; //available permits
    private int waitingCount; //how many thread currently blocked

    //basic constructor: start with fix num of permits
    public Semaphore(int initial) {
        this.value = initial;
        this.waitingCount = 0;
    }
    //overload constructor: call normal constructor above
    public Semaphore(int initial, boolean fair) {
        this(initial);
    }
    

///wake up every thread they are waiting
    public synchronized void up() {
        ++value;
        notifyAll(); // wake ALL threads blocked in down()/tryDown()
    }

//if non free, thread will wait here until someone call
    public synchronized void down() throws InterruptedException {
        waitingCount++;
        while (value == 0) {
            wait(); // release lock + suspend thread (Week 6)
        }
        waitingCount--;
        --value;
    }

///give up after timeoutMs will waiting
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
    public synchronized String toString() {
        return "Semaphore [permits = " + value + ", waiting = " + waitingCount + "]";
    }
}
