package ccpassignment;

import javax.swing.*;
import java.awt.*;
import java.util.Random;
import java.util.concurrent.TimeUnit;
/**
 *
 * @author Xenia Thor
 */
public class Semaphore {
    private int value;
    
    //constructor 1
    public Semaphore (int initial) {
        this.value = initial;
    }
    
    //constructor 2
    public Semaphore (int initial, boolean fair){
        this.value = initial;
    }
    
    //up (release one permit
    public synchronized void up(){
        ++value;
        notifyAll(); //wake all thread and waiting in down()/tryDown()
    }
    
    //down (one permit, block if non available
    public synchronized void down() throws InterruptedException {
        while (value ==0) {
            wait();
        }
        --value;
    }
    
    //try down with timeout (extension)
    public synchronized boolean tryDown(long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (value == 0) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false; // patience expired — customer leaves
            }
            wait(remaining); // wait at most 'remaining' ms then recheck
        }
        --value;
        return true; // got permit successfully
    }
}
