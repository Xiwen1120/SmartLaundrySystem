package ccpassignment;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import javax.imageio.ImageIO;
import java.net.URL;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.sampled.*;


/**
 *
 * @author Xenia Thor
 */

public class SmartLaundrySimulation extends JFrame {

    // Constants
    public static final int NUM_WASHERS = 6;
    public static final int NUM_DRYERS = 4;
    public static final int NUM_KIOSKS = 2;
    public static final int NUM_BASKETS = 5;
    public static final int TOTAL_CUSTOMERS = 50;

    //Semaphores — Mechanism 1
    //fair=true → FIFO ordering, no starvation
    public static final Semaphore semWasher = new Semaphore(NUM_WASHERS, true);
    public static final Semaphore semDryer  = new Semaphore(NUM_DRYERS, true);
    public static final Semaphore semKiosk  = new Semaphore(NUM_KIOSKS, true);
    public static final Semaphore semBasket = new Semaphore(NUM_BASKETS, true);
    public static final NestedMonitor paymentMonitor = new NestedMonitor (semKiosk);
    
//  public static final NestedMonitor paymentMonitor = new NestedMonitor (semKiosk);
    //ReentrantLock + Condition — Mechanism 2
    //reentrantLock = guard share statistics
    //condition = for allow wait/signal
    public static final ReentrantLock statsLock  = new ReentrantLock();
    //public static final Condition ownerAlert = statsLock.newCondition();
    public static int  statDone = 0;
    public static int  statLeft = 0;
    public static long statTotalMs = 0;
    public static long statLeftMs = 0; 

    // AtomicInteger counters — Mechanism 3 
    public static final AtomicInteger activeWashers = new AtomicInteger(0);
    public static final AtomicInteger activeDryers = new AtomicInteger(0);
    public static final AtomicInteger peakWashers = new AtomicInteger(0);
    public static final AtomicInteger peakDryers = new AtomicInteger(0);
    public static final AtomicInteger payQueueSize = new AtomicInteger(0);
    public static final AtomicInteger arrivedCount = new AtomicInteger(0);
    public static final AtomicInteger doneCount = new AtomicInteger(0);
    
    public static void updateMax (AtomicInteger atom, int newVal){
        int cur;
        do {
            cur = atom.get ();
            if (newVal <= cur)
                return;
        } while (!atom.compareAndSet(cur, newVal));
    }
    
    // Per-slot busy state (guarded by machineLock)
    // Each index represents a physical machine slot.
    public static final boolean[] washerBusy = new boolean[NUM_WASHERS];
    public static final int[] washerOwner = new int[NUM_WASHERS];
    public static final boolean[] dryerBusy = new boolean[NUM_DRYERS];
    public static final int[] dryerOwner  = new int[NUM_DRYERS];
    public static final boolean[] basketBusy = new boolean[NUM_BASKETS];
    public static final int[] basketOwner = new int[NUM_BASKETS];
    public static final boolean[] kioskBusy = new boolean[NUM_KIOSKS];
    public static final int[] kioskOwner = new int[NUM_KIOSKS];
    public static final boolean[] kioskDown = new boolean[NUM_KIOSKS];
    
    // Lock protecting all *Busy / *Owner arrays
    public static final Object machineLock = new Object();

    // Bonus scenario 
    public static volatile boolean running = false;
    public static volatile boolean paymentDown = false;
    public static volatile boolean ownerCalled = false;

//    //BlockingQueue for log messages — Mechanism 4
//    public static final LinkedBlockingQueue<String> logQueue =
//            new LinkedBlockingQueue<>(2000);

    // Customer list (for dot-grid display)
    public static final List<CustomerThread> customers =
            Collections.synchronizedList(new ArrayList<>());

    // Customer states (for dot colours)
    public enum CustomerState {
        ARRIVED, BASKET, WASHING, DRYING, PAYMENT, DONE, LEFT, FAILED
    }

    // GUI design
    // Status bars
    private CustomerDotPanel dotPanel;
    private CustomerArrivePanel customerArrivePanel; 
    private JLabel lblProgress;
    private colorBtn btnStart, btnStop, btnReset, btnCancel;
    private JLabel lblTitle;
    
    private JProgressBar progresBarWasher, progressBarDryer, pbKiosk, pbBasket, pbTotalLeft, pbTotalDone;
    // Machine icon panels
    private MachineIconPanel[] washerPanels = new MachineIconPanel[NUM_WASHERS];
    private MachineIconPanel[] dryerPanels  = new MachineIconPanel[NUM_DRYERS];
    private MachineIconPanel[] kioskPanels  = new MachineIconPanel[NUM_KIOSKS];
    private MachineIconPanel[] basketPanels = new MachineIconPanel[NUM_BASKETS];


    public SmartLaundrySimulation() {
        super("Smart Laundry Facility Simulation");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int width = (int)(screen.width  * 0.95);
        int height = (int)(screen.height * 0.95);
        setSize(width, height);
        setMinimumSize(new Dimension(1000, 700));
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        setLocationRelativeTo(null);
        new javax.swing.Timer(100, e -> refreshBtn()).start(); //10 fps GUI refresh
        buildUI();
    }

    private void buildUI() {
        Color purple = new Color(157, 12, 247);
        Color bgGreyPurple = new Color(52, 7, 79);
        Color panelGrey  = new Color(210, 205, 220);

        getContentPane().setBackground(bgGreyPurple);
        setLayout(new BorderLayout(0, 0));

        // JPanel top bar
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(purple);
        topBar.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));

        lblTitle = new JLabel("Smart Laundry Facility Simulation");
        lblTitle.setFont(loadFont("Times New Roman", Font.BOLD, 30));
        lblTitle.setForeground(Color.WHITE);
        lblTitle.setHorizontalAlignment(JLabel.CENTER);
        topBar.add(lblTitle, BorderLayout.CENTER);

        add(topBar, BorderLayout.NORTH);

        // STATUS BAR
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(purple);
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 16, 8, 16));
        JLabel lblStatus = new JLabel("Status");
        lblStatus.setFont(new Font("Times New Roman", Font.BOLD, 14));
        lblStatus.setForeground(Color.WHITE);
        JPanel statusContent = new JPanel(new BorderLayout(0, 2));
        statusContent.setBackground(purple);
        statusContent.add(lblStatus, BorderLayout.NORTH);
        JPanel pbRow = new JPanel(new GridLayout(1, 6, 8, 0));
        pbRow.setBackground(purple);
        progresBarWasher = makePB("Waching Machine", 6);
        progressBarDryer  = makePB("Dryer", 4);
        pbKiosk  = makePB("Payment Kiosk", 2);
        pbBasket = makePB("Basket", NUM_BASKETS);
        pbTotalLeft = makePB("Total Left", TOTAL_CUSTOMERS);
        pbTotalDone = makePB("Total Done", TOTAL_CUSTOMERS);
        pbRow.add(wrapPB("Waching Machine", progresBarWasher));
        pbRow.add(wrapPB("Dryer", progressBarDryer));
        pbRow.add(wrapPB("Payment Kiosk", pbKiosk));
        pbRow.add(wrapPB("Basket", pbBasket));
        pbRow.add(wrapPB("Total Left", pbTotalLeft));
        pbRow.add(wrapPB("Total Done", pbTotalDone));
        statusContent.add(pbRow, BorderLayout.CENTER);
        statusContent.add(pbRow, BorderLayout.CENTER);
        statusBar.add(statusContent);

        // combine title + status into north
        JPanel northPanel = new JPanel(new BorderLayout());
        northPanel.add(topBar, BorderLayout.NORTH);
        northPanel.add(statusBar, BorderLayout.SOUTH);
        add(northPanel, BorderLayout.NORTH);

        // MAIN AREA
        JPanel main = new JPanel(new BorderLayout(6, 6));
        main.setBackground(bgGreyPurple);
        main.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        // LEFT: Washing machines column
        JPanel leftCol = buildWasherColumn(panelGrey);
        leftCol.setPreferredSize(new Dimension(310, 0));

        // CENTER-RIGHT: Dryer | Kiosk+Baskets | Log
        JPanel centerRight = new JPanel(new GridLayout(1, 3, 6, 0));
        centerRight.setBackground(bgGreyPurple);

        JPanel dryerCol = buildDryerColumn(panelGrey);
        JPanel rightCols = buildRightSection(panelGrey);

        centerRight.add(dryerCol);
        centerRight.add(rightCols);

        main.add(leftCol, BorderLayout.WEST);
        main.add(centerRight, BorderLayout.CENTER);

        // BOTTOM: customer dots + controls
        JPanel bottomSection = new JPanel(new BorderLayout(0, 4));
        bottomSection.setBackground(bgGreyPurple);
        bottomSection.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 10));

        // Dot panel
        JPanel dotWrapper = new JPanel(new BorderLayout());
        dotWrapper.setBackground(panelGrey);
        dotWrapper.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(140, 130, 160), 1),
            "Customer (Status Flow)",
            TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Times New Roman", Font.BOLD, 13),
            new Color(60, 20, 100)));

        dotPanel = new CustomerDotPanel();
        dotPanel.setPreferredSize(new Dimension(0, 65));
        dotWrapper.add(dotPanel, BorderLayout.CENTER);
        bottomSection.add(dotWrapper, BorderLayout.CENTER);

        // Control bar
        JPanel controlBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        controlBar.setBackground(bgGreyPurple);

        lblProgress = new JLabel("0/50");
        lblProgress.setFont(new Font("Times New Roman", Font.BOLD, 14));
        lblProgress.setForeground(Color.WHITE);
        lblProgress.setHorizontalAlignment(SwingConstants.CENTER);
        lblProgress.setPreferredSize(new Dimension(100, 36));
        lblProgress.setOpaque(true);
        lblProgress.setBackground(new Color(80, 80, 100));
        lblProgress.setBorder(BorderFactory.createLineBorder(Color.WHITE, 1));

        btnStart  = new colorBtn("Start",  new Color(34, 197, 94));   // solid green
        btnStop   = new colorBtn("Stop",   new Color(239, 68, 68));   // solid red
        btnReset  = new colorBtn("Reset",  new Color(234, 179, 8));   // solid yellow
        btnCancel = new colorBtn("Cancel", new Color(6, 182, 212));   // solid cyan
        btnStop.setEnabled(false);     
        btnStart.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                startSim();
            }
        });
        btnStop.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                stopSim();
            }
        });
        btnReset.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                resetSim();
            }
        });
        btnCancel.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelSim();
            }
        });

        controlBar.add(lblProgress);
        controlBar.add(btnStart);
        controlBar.add(btnStop);
        controlBar.add(btnReset);
        controlBar.add(btnCancel);
        bottomSection.add(controlBar, BorderLayout.SOUTH);

        add(main, BorderLayout.CENTER);
        add(bottomSection, BorderLayout.SOUTH);
    }

    // Round panel design
    private class RoundedPanel extends JPanel {
        private final int radius;
        
        public RoundedPanel (int radius) {
            this.radius = radius;
            setOpaque (false);
        } 
        
       @Override
        protected void paintComponent (Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
        }
    }
  
    // washing machine column 
    private JPanel buildWasherColumn(Color bg) {
        JPanel column = new JPanel(new BorderLayout());
        column.setBackground(bg);
        column.setBorder(compoundBorder("Waching Machine"));
        
        RoundedPanel grid = new RoundedPanel (61);
//        JPanel grid = new JPanel(new GridLayout(NUM_WASHERS, 1, 0, 4));
        grid.setBackground(Color.GRAY);
        grid.setLayout(new GridLayout (NUM_WASHERS, 1,0,4));
        grid.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));

        for (int i = 0; i < NUM_WASHERS; i++) {
            washerPanels[i] = new MachineIconPanel(MachineIconPanel.Type.WASHER, "W" + (i+1));
            grid.add(washerPanels[i]);
        }
        column.add(grid, BorderLayout.CENTER);
        return column;
    }

    // dryer column
    private JPanel buildDryerColumn(Color bg) {
        JPanel col = new JPanel(new BorderLayout());
        col.setBackground(bg);
        col.setBorder(compoundBorder("Dryer"));
        
        RoundedPanel grid = new RoundedPanel (61);
        grid.setLayout(new GridLayout (NUM_DRYERS, 1,0,4));
        grid.setBackground(Color.GRAY);
        grid.setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));

        for (int i = 0; i < NUM_DRYERS; i++) {
            dryerPanels[i] = new MachineIconPanel(MachineIconPanel.Type.DRYER, "D" + (i+1));
            grid.add(dryerPanels[i]);
        }
        col.add(grid, BorderLayout.CENTER);
        return col;
    }

    // Build right section: kiosk + baskets + log 
private JPanel buildRightSection(Color bg) {
    JPanel right = new JPanel(new GridLayout(2, 1, 0, 6));
    right.setBackground(new Color(185, 175, 200));

    // Top-right: Kiosk + Baskets side by side (unchanged)
    JPanel topRight = new JPanel(new GridLayout(1, 2, 6, 0));
    topRight.setBackground(new Color(185, 175, 200));

    JPanel kioskCol = new JPanel(new BorderLayout());
    kioskCol.setBackground(bg);
    kioskCol.setBorder(compoundBorder("Payment Kiosk"));
    JPanel kioskGrid = new JPanel(new GridLayout(NUM_KIOSKS, 1, 0, 8));
    kioskGrid.setBackground(bg);
    kioskGrid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    for (int i = 0; i < NUM_KIOSKS; i++) {
        kioskPanels[i] = new MachineIconPanel(MachineIconPanel.Type.KIOSK, "ATM" + (i+1));
        kioskGrid.add(kioskPanels[i]);
    }
    kioskCol.add(kioskGrid, BorderLayout.CENTER);

    JPanel basketCol = new JPanel(new BorderLayout());
    basketCol.setBackground(bg);
    basketCol.setBorder(compoundBorder("Baskets"));
    JPanel basketGrid = new JPanel(new GridLayout(3, 2, 6, 8));
    basketGrid.setBackground(bg);
    basketGrid.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    for (int i = 0; i < NUM_BASKETS; i++) {
        basketPanels[i] = new MachineIconPanel(MachineIconPanel.Type.BASKET, "BK" + (i+1));
        basketGrid.add(basketPanels[i]);
    }
    basketCol.add(basketGrid, BorderLayout.CENTER);

    topRight.add(kioskCol);
    topRight.add(basketCol);

    // Bottom-right: Customer Arrive panel (replaces Activity Log)
    JPanel arrivePanel = new JPanel(new BorderLayout());
    arrivePanel.setBackground(bg);
    arrivePanel.setBorder(compoundBorder("Customer Arrive"));

    customerArrivePanel = new CustomerArrivePanel();
    JScrollPane sp = new JScrollPane(customerArrivePanel);
    sp.setBorder(null);
    sp.setBackground(bg);
    arrivePanel.add(sp, BorderLayout.CENTER);

    right.add(topRight);
    right.add(arrivePanel);
    return right;
}

private class CustomerArrivePanel extends JPanel {
    private BufferedImage iconCustomer = null;
    private BufferedImage iconFailed   = null;
    private BufferedImage iconProcess  = null;

    public CustomerArrivePanel() {
        try {
            iconCustomer = ImageIO.read(
                SmartLaundrySimulation.class.getResource("icon_customer.png"));
        } catch (Exception e) { System.out.println("icon_customer.png not found"); }
        try {
            iconFailed = ImageIO.read(
                SmartLaundrySimulation.class.getResource("icon_failled.png"));
        } catch (Exception e) { System.out.println("icon_failled.png not found"); }
        try {
            iconProcess = ImageIO.read(
                SmartLaundrySimulation.class.getResource("icon_processCustomer.png"));
        } catch (Exception e) { System.out.println("icon_processCustomer.png not found"); }

        setOpaque(true);
        setBackground(new Color(218, 212, 228));
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // CONCURRENT CONCEPT: synchronized read of shared list (Week 5)
        // Must hold list lock during iteration to prevent ConcurrentModificationException
        // from arrival thread adding customers or cancelSim() clearing the list
        List<int[]> snapshot = new ArrayList<>();
        synchronized (SmartLaundrySimulation.customers) {
            for (CustomerThread c : SmartLaundrySimulation.customers) {
                snapshot.add(new int[]{ c.id, c.customerState.ordinal() });
            }
        }

        if (snapshot.isEmpty()) {
            g2.setFont(new Font("Times New Roman", Font.ITALIC, 11));
            g2.setColor(new Color(120, 110, 140));
            g2.drawString("Waiting for customers...", 10, getHeight() / 2 + 4);
            return;
        }

        int iconSize = 42, gap = 6;
        int perRow   = Math.max(1, (getWidth() - 10) / (iconSize + gap));

        int col = 0, row = 0;
        for (int[] entry : snapshot) {
            int id       = entry[0];
            int stateOrd = entry[1];

            // CONCURRENT CONCEPT: volatile read (Week 3 / JMM)
            // CustomerState is volatile in CustomerThread
            // EDT always reads the latest value written by the customer thread
            boolean done = (stateOrd == CustomerState.DONE.ordinal() ||
                            stateOrd == CustomerState.LEFT.ordinal());
            boolean failed = (stateOrd == CustomerState.FAILED.ordinal());
            boolean inProcess = (stateOrd == CustomerState.BASKET.ordinal()  ||
                                 stateOrd == CustomerState.WASHING.ordinal() ||
                                 stateOrd == CustomerState.DRYING.ordinal()  ||
                                 stateOrd == CustomerState.PAYMENT.ordinal());

            // DONE or LEFT — remove icon from panel
            if (done) continue;

            int px = 6 + col * (iconSize + gap);
            int py = 6 + row * (iconSize + gap + 14);

            // Pick icon based on state
            BufferedImage icon;
            if (failed) icon = iconFailed;   // 10% machine/kiosk fail
            else if (inProcess) icon = iconProcess;  // using basket/washer/dryer/kiosk
            else icon = iconCustomer; // just arrived

            if (icon != null) {
                g2.drawImage(icon, px, py, iconSize, iconSize, this);
            } else {
                // Fallback circle if image file missing
                g2.setColor(failed ? new Color(252, 8, 73) :
                            inProcess ? new Color(120, 200, 255) :
                            new Color(255, 215, 80));
                g2.fillOval(px, py, iconSize, iconSize);
            }

            // Customer ID label below icon
            g2.setFont(new Font("Times New Roman", Font.BOLD, 9));
            g2.setColor(new Color(50, 20, 80));
            String lbl = "C-" + id;
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(lbl,
                px + (iconSize - fm.stringWidth(lbl)) / 2,
                py + iconSize + 11);

            col++;
            if (col >= perRow) { col = 0; row++; }
        }

        // Adjust height so JScrollPane scrolls correctly
        int rows   = (int) Math.ceil((double) snapshot.size() / perRow);
        int needed = rows * (iconSize + gap + 14) + 12;
        if (getPreferredSize().height != needed) {
            setPreferredSize(new Dimension(getWidth(), needed));
            revalidate();
        }
    }
}

    // Progress bar helpers
    private JProgressBar makePB(String label, int max) {
        JProgressBar pb = new JProgressBar(0, max);
        pb.setValue(0);
        pb.setStringPainted(true);
        pb.setString("0/" + max);
        pb.setFont(new Font("Times New Roman", Font.BOLD, 11));
        pb.setForeground(new Color(180, 230, 180));
        pb.setBackground(new Color(70, 30, 110));
        return pb;
    }

    private JPanel wrapPB(String label, JProgressBar pb) {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setBackground(new Color(90, 20, 140));
        JLabel lbl = new JLabel(label);
        lbl.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        lbl.setForeground(Color.WHITE);
        p.add(lbl, BorderLayout.NORTH);
        p.add(pb, BorderLayout.CENTER);
        return p;
    }

    // Border helper 
    private Border compoundBorder(String title) {
        return BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(130, 110, 160), 1),
            title,
            TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Times New Roman", Font.BOLD, 14),
            new Color(50, 20, 80));
    }

    // Button ui
    private static class colorBtn extends JButton {
        private final Color baseColor;
        
        colorBtn (String text, Color color) {
            super(text);
            this.baseColor = color;
            setContentAreaFilled(true);
            setOpaque(true);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setForeground(Color.WHITE);
            setFont(new Font("Times New Roman", Font.BOLD, 14));
            setBorder(BorderFactory.createEmptyBorder(8, 24, 8, 24));
        }
        
        @Override
    protected void paintComponent(Graphics g) {

        Graphics2D g2 = (Graphics2D) g.create();

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);

        // draw background
        g2.setColor(baseColor);
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 15, 15);

        // draw text
        FontMetrics fm = g2.getFontMetrics(getFont());

        int x = (getWidth() - fm.stringWidth(getText())) / 2;
        int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();

        g2.setColor(getForeground());
        g2.setFont(getFont());
        g2.drawString(getText(), x, y);

        g2.dispose();
        }
    }
    

    private Font loadFont(String name, int style, int size) {
        return new Font(name, style, size);
    }

//button for start, stop and reset (need added cancel button)
    private void startSim() {
        btnStart.setEnabled(false);
        btnStop .setEnabled(true);
        btnCancel.setEnabled(true);
        running = true;
        hardReset();
        
        //arrival thread: 50 customer
        Thread arrival = new Thread (new Runnable() {
            @Override
            public void run() {
                Random rng = new Random ();
                for (int i = 1; i <= TOTAL_CUSTOMERS && running; i++) {
                    CustomerThread c = new CustomerThread (i);
                    customers.add(c);
                    arrivedCount.incrementAndGet();
                    c.start();
                    try {
                        Thread.sleep(rng.nextInt(3001)); //0-3s
                    } catch (InterruptedException ex) {
                        break;
                    }
                }
            }
        }, "ArrivalThread");
        arrival.setDaemon(true);
        arrival.start();


        // Bonus monitor thread (bonussss mark crash the paymennt kiosk )
        Thread bonus = new Thread(new Runnable() {
        @Override
            public void run() {
    Random random = new Random();
    if (random.nextInt(100) < 10) {
        //messageTerminal("Payment kiosk failed. Customer retrying...");

        int failedKiosk = random.nextInt(NUM_KIOSKS);

        synchronized(machineLock){
            kioskDown[failedKiosk] = true;
        }

        messageTerminal(
            "ATM" + (failedKiosk + 1) + " failed"
        );


        try {
            Thread.sleep(2000);
        } catch (InterruptedException ex) {
            Logger.getLogger(SmartLaundrySimulation.class.getName()).log(Level.SEVERE, null, ex);
        }


        synchronized(machineLock){
            kioskDown[failedKiosk] = false;
        }

        messageTerminal(
            "ATM" + (failedKiosk + 1) + " repaired"
        );
    }
    String[] reasons = {
        "Payment gateway timeout",
        "Payment service is busy now"
    };
    try {
        // Pick a random moment 10–30s into the run to trigger the outage.
        // Deliberately NOT gated on queue size — the outage causes the
        // congestion, the congestion doesn't cause the outage.
        long triggerAt = 10_000 + random.nextInt(20_000);
        boolean triggered = false;

        while (running) {
            long elapsed = System.currentTimeMillis() - simStartTime;

            if (!triggered && elapsed >= triggerAt) {
                triggered = true;
                //bonus requirement
                paymentDown = true;
                paymentMonitor.setSystemDown(true);

                String reason = reasons[random.nextInt(reasons.length)];
                messageTerminal("[System] " + reason + " — BOTH kiosks down!");

                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        lblTitle.setText("⚠ " + reason + " Repairing...");
                        lblTitle.setForeground(Color.RED);
                    }
                });

                Thread.sleep(15_000); // stays down long enough for the queue to actually build

                paymentDown = false;
                paymentMonitor.setSystemDown(false);
                messageTerminal("[System] Payment kiosk repaired.");

                SwingUtilities.invokeLater(new Runnable() {
                    @Override public void run() {
                        lblTitle.setText("Smart Laundry Facility Simulation");
                        lblTitle.setForeground(Color.WHITE);
                    }
                });
            }
            Thread.sleep(400);
        }
    } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
    }
}
    }, "BonusMonitor");

    bonus.setDaemon(true);
    bonus.start();
    }

    //using volatile
    private void stopSim() {
        running = false;
        btnStop.setEnabled(false);
        btnStart.setEnabled(true);
        System.out.println("Simulation stopped.");
    }

    private void resetSim() {
        running = false;
        for (int i=0;i<customers.size();i++) {
            CustomerThread c = customers.get(i);
            if (c.isAlive()){
                c.interrupt();
            }
        }
        hardReset();
        startSim();
}
    
    private void cancelSim(){
        //using volatile concept for stop func
        running = false; 
        statsLock.lock();
        try {
            statDone = 0;
            statLeft = 0;
            statTotalMs = 0;
            statLeftMs = 0;
        } finally {
            statsLock.unlock ();
        }
        
        int count = 0;
        synchronized (customers) {
            for (CustomerThread c : customers) {
                if (c.isAlive()) {
                    c.interrupt();
                    count++;
                }
            } 
        } final int interrupted = count;
        //UI update
        SwingUtilities.invokeLater(() -> {
            running = false;   
            hardReset(); //clear data
            refreshBtn();
            
            btnCancel.setEnabled(false);
            btnStart .setEnabled(true);
            btnStop  .setEnabled(false);
            System.out.println( "Already cancel, please press START for rerun the system");
    });
      
    }

    private void hardReset() {
        simStartTime = System.currentTimeMillis();
        statsLock.lock();
        try { 
            statDone = 0; 
            statLeft = 0; 
            statTotalMs = 0;
            statLeftMs = 0;
        } finally { 
            statsLock.unlock(); 
        }
        
        semWasher.reset(NUM_WASHERS);
        semDryer.reset(NUM_DRYERS);
        semKiosk.reset(NUM_KIOSKS);
        semBasket.reset(NUM_BASKETS);
        activeWashers.set(0); 
        activeDryers.set(0);
        peakWashers.set(0); 
        peakDryers.set(0);
        payQueueSize.set(0); 
        arrivedCount.set(0);
        doneCount.set(0);
        paymentDown = false;  ownerCalled = false;
        synchronized (machineLock) {
            Arrays.fill(washerBusy, false); Arrays.fill(washerOwner, 0);
            Arrays.fill(dryerBusy, false); Arrays.fill(dryerOwner,  0);
            Arrays.fill(basketBusy, false); Arrays.fill(basketOwner, 0);
            Arrays.fill(kioskBusy, false); Arrays.fill(kioskOwner,  0);
            Arrays.fill(kioskDown, false);
        }
        customers.clear();
    }

    private void refreshBtn() {
        // Drain log queue into text area
        // Update all machine icon panels
        updateAllPanels();

        // Progress bars
        progresBarWasher.setValue(activeWashers.get());
        progresBarWasher.setString(activeWashers.get() + "/" + NUM_WASHERS);
        progressBarDryer .setValue(activeDryers.get());
        progressBarDryer .setString(activeDryers.get()  + "/" + NUM_DRYERS);
        pbKiosk .setValue(NUM_KIOSKS - semKiosk.availablePermits());
        pbKiosk .setString((NUM_KIOSKS - semKiosk.availablePermits()) + "/" + NUM_KIOSKS);
        pbBasket.setValue(NUM_BASKETS - semBasket.availablePermits());
        pbBasket.setString((NUM_BASKETS - semBasket.availablePermits()) + "/" + NUM_BASKETS);

        // Customer counter
        statsLock.lock();
        int done, left; 
        long ms, leftMs;
        try { 
            done = statDone; 
            left = statLeft; 
            ms = statTotalMs; 
            leftMs = statLeftMs;
        } finally { statsLock.unlock(); }
        lblProgress.setText((done + left) + "/50");
        
        //update total done progress bar
        pbTotalDone.setValue(done);
        pbTotalDone.setString(done + "/" + TOTAL_CUSTOMERS);
        
        //update total left progress bar
        pbTotalLeft.setValue(left);
        pbTotalLeft.setString(left + "/" + TOTAL_CUSTOMERS);

        // Customer dot grid
        dotPanel.repaint();
        if (customerArrivePanel != null) customerArrivePanel.repaint();

        // Final stats
        if (running && (done + left) >= TOTAL_CUSTOMERS) {
            boolean anyAlive = false;
            synchronized (customers) {
                for (CustomerThread c : customers)
                    if (c.isAlive()) { anyAlive = true; break; }
            }
            if (!anyAlive) {
                running = false;
                printFinalStats(done, left, ms, leftMs);
                btnStart.setEnabled(true);
                btnStop .setEnabled(false);
            }
        }
    }
    

    private void updateAllPanels() {
        synchronized (machineLock) {
            for (int i = 0; i < NUM_WASHERS; i++)
            {
                washerPanels[i].setStatus(washerBusy[i], washerOwner[i]);
            }
            for (int i = 0; i < NUM_DRYERS; i++) {
                dryerPanels[i].setStatus(dryerBusy[i], dryerOwner[i]);
            }
            for (int i = 0; i < NUM_KIOSKS; i++) {
                kioskPanels[i].setStatus(kioskBusy[i], kioskOwner[i]);
                kioskPanels[i].setDown(kioskDown[i]);
            }
            for (int i = 0; i < NUM_BASKETS; i++) 
            {
                basketPanels[i].setStatus(basketBusy[i], basketOwner[i]);
            }
        }
    }

    private void printFinalStats(int done, int left, long ms, long leftMs) {
        double avg = done > 0 ? (ms / (double) done) / 1000.0 : 0;
        double avgLeft = left >0 ? (leftMs/(double) left) / 1000.0:0;
        System.out.println("Total Customers: " + TOTAL_CUSTOMERS);
        System.out.println("✅ Completed: " + done);
        System.out.println("🚶 Left (impatient): " + left);
        System.out.println(String.format("Avg time before giving up: %.2f s", avgLeft));
        System.out.println ("Washers: " + peakWashers.get() + " / " + NUM_WASHERS);
        System.out.println("Dryers: " + peakDryers.get()  + " / " + NUM_DRYERS);
        System.out.println(String.format("Total Simulation Duration Time  : %.1f s", 
            (System.currentTimeMillis() - simStartTime) / 1000.0));
    }
    
    //for the start time and duration time used
    private static long simStartTime = 0;
    // Thread-safe log to activity area
    public static void messageTerminal (String msg) {
        long elapsed = System.currentTimeMillis() - simStartTime;
        String format = String.format ( "[%5.1fs] %s", elapsed / 1000.0 , msg);
//        logQueue.offer(format);
        System.out.println(format);
    }

    // Beep on payment complete 
    public static void playBeep() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    java.net.URL soundUrl = SmartLaundrySimulation.class.getResource("/MP3Sound/paymentSuccessSound.wav");

                    if (soundUrl == null) {
                        System.out.println("Sound not found");
                        return;
                    }

                    AudioInputStream audioIn = AudioSystem.getAudioInputStream(soundUrl);
                    Clip clip = AudioSystem.getClip();
                    clip.open(audioIn);
                    clip.start();
                } catch (Exception e) {
                    System.out.println("Cannot play the sound");
                }
            }
        }, "PaymentSoundThread").start();
    }
    

    public static void main(String[] args) {
    // Runnable is an interface with one method: run()
    // We use it here to safely start the GUI on the correct thread
    final Runnable startGUI = new Runnable() {
        public void run() {
            try { 
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); 
            } catch (Exception ignored) {}
            new SmartLaundrySimulation().setVisible(true);
        }
    };
    // SwingUtilities.invokeLater() makes sure the window opens on the GUI thread
    SwingUtilities.invokeLater(startGUI);
    }
}
