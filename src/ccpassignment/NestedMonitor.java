package ccpassignment;

/**
 *
 * @author Xenia Thor
 */
public class NestedMonitor {
    //private encapsulate state
    private int queueCount = 0; //customers payment area
    private int waitingCount = 0; //customers block
    private boolean systemDown = false;
    
    //constructor
    // payment Monitor 
    private final Semaphore semKiosk;
    public NestedMonitor (Semaphore semKiosk) {
        this.semKiosk = semKiosk;
    }
    
    public synchronized void customerArrived (int customerID) {
        queueCount++;
        SmartLaundrySimulation.messageTerminal(
        "[PaymentQueue]Customer-" +customerID + "entered queue. Queue size: " + queueCount);
        if (queueCount >=30 && !SmartLaundrySimulation.ownerCalled) {
            SmartLaundrySimulation.ownerCalled = true;
            SmartLaundrySimulation.messageTerminal (
            "[PaymentQueue] Customer called! " +
                queueCount + " customers waiting!");
        }
    }
    //customer (queue count)
    //synchronized = nutual exclusion
    // notifyAll() = wake any threads monitoring queue
    public synchronized void customerLeft (int customerID) {
        if (queueCount > 0) queueCount--;
        SmartLaundrySimulation.messageTerminal(
            "[PaymentQueue] Customer-" + customerID +
            " left queue. Remaining: " + queueCount);
        notifyAll(); //notify anyone monitoring this state
    }
    
    public synchronized boolean waitIfSystemDown(int customerId, long timeoutMs)
            throws InterruptedException {
        if (!systemDown) 
            return true; // fast path — system is fine
 
        //long deadline = System.currentTimeMillis() + timeoutMs;
        waitingCount++;
        SmartLaundrySimulation.messageTerminal(
            "[PaymentQueue] Customer-" + customerId +
            " waiting — kiosks DOWN. Blocked: " + waitingCount);
        
        while (systemDown) {
            wait();
        }
        waitingCount--;
        SmartLaundrySimulation.messageTerminal(
            "[PaymentQueue] Customer-" + customerId +
            " woke up — system restored, proceeding.");
        return true;
        }
    
    public synchronized void setSystemDown (boolean down) {
        this.systemDown = down;
        if (!down) {
            SmartLaundrySimulation.messageTerminal(
                "[PaymentQueue] System RESTORED. Waking " +
                waitingCount + " blocked customers.");
            notifyAll(); // Week 6 — wake ALL waiting threads
        } else {
            SmartLaundrySimulation.messageTerminal(
                "[PaymentQueue] System DOWN. " +
                queueCount + " customers affected.");
        }
    }
    
    //Get queue size ( GUI )
    public synchronized int getQueueSize (){
        return queueCount;
    }
    
    public synchronized boolean isSystemDown() {
        return systemDown;
    }
    
    public synchronized int getWaitingCount() {
        return waitingCount;
    }
    
    public int getKioskWaitingCount() {
        return semKiosk.getWaitingCount();
    }
}

