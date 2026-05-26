package ccpassignment;

import javax.swing.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;


public class MachineIconPanel extends JPanel {

    public enum Type { WASHER, DRYER, KIOSK, BASKET }

    private final Type type;
    private final String label;
    private boolean busy  = false;
    private int owner = 0;

    private static BufferedImage imageWasher;
    private static BufferedImage imageDryer  = null;
    private static BufferedImage imageKiosk  = null;
    private static BufferedImage imageBasket = null;
    private static boolean imagesLoaded = false;

    // Tint colours when busy
    private static final Color TINT_WASHER = new Color(0, 212, 240); //blue
    private static final Color TINT_DRYER  = new Color(245, 128, 32); //orange
    private static final Color TINT_KIOSK  = new Color(66, 245, 66); //green
    private static final Color TINT_BASKET = new Color(250, 217, 2); //yellow

    private static final Color C_BOX   = new Color(218, 213, 228);
    private static final Color C_LABEL = new Color(30,  15,  55);
    private static final Color C_BUSY  = new Color(50,  30, 110);

    // Load all 4 icons from src folder (inside the jar as resources)        
    private static void imagesLoadFromFile() {
        try {
            imageWasher = ImageIO.read(
                MachineIconPanel.class.getResource("icon_washer.png")
            );

            imageDryer = ImageIO.read(
                MachineIconPanel.class.getResource("icon_dryer.png")
            );

            imageKiosk = ImageIO.read(
                MachineIconPanel.class.getResource("icon_kiosk.png")
            );

            imageBasket = ImageIO.read(
               MachineIconPanel.class.getResource("icon_basket.png")
            );

        } catch (IOException e) {
            System.out.println("Image cannot load");
        }
    }

    public MachineIconPanel(Type type, String label) {
        this.type  = type;
        this.label = label;
        setOpaque(true);
        setBackground(C_BOX);
        setPreferredSize(new Dimension(0, 78));
        imagesLoadFromFile();
    }

    public void setStatus(boolean busy, int ownerId) {
        this.busy  = busy;
        this.owner = ownerId;
        repaint();
    }

    private Color getTint() {
        if (type == Type.WASHER) return TINT_WASHER;
        if (type == Type.DRYER)  return TINT_DRYER;
        if (type == Type.KIOSK)  return TINT_KIOSK;
        return TINT_BASKET;
    }

    private BufferedImage getIcon() {
        if (type == Type.WASHER) return imageWasher;
        if (type == Type.DRYER)  return imageDryer;
        if (type == Type.KIOSK)  return imageKiosk;
        return imageBasket;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_QUALITY);

        int W = getWidth(), H = getHeight();

        // Tint background when busy
        if (busy) {
            g2.setColor(getTint());
            g2.fillRect(0, 0, W, H);
        }

        // Draw icon
        int iconSize = Math.min(H - 8, 62);
        int ix = 8;
        int iy = (H - iconSize) / 2;

        BufferedImage icon = getIcon();
        if (icon != null) {
            g2.drawImage(icon, ix, iy, iconSize, iconSize, this);
        } else {
            // Fallback: draw a simple placeholder rectangle
            g2.setColor(new Color(160, 150, 180));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(ix, iy, iconSize, iconSize, 8, 8);
            g2.setFont(new Font("New Times Roman", Font.BOLD, 9));
            g2.setColor(C_LABEL);
            g2.drawString(label, ix + 4, iy + iconSize / 2 + 4);
        }

        // Label text: "W1", "D3", "ATM1", "BK2"
        int lx = ix + iconSize + 12;
        int ly = H / 2 + 5;

        g2.setFont(new Font("New Times Roman", Font.BOLD, 16));
        g2.setColor(busy ? C_BUSY : C_LABEL);
        g2.drawString(label, lx, ly);

        // Customer ID below label when busy
        if (busy) {
            g2.setFont(new Font("New Times Roman", Font.PLAIN, 11));
            g2.setColor(C_BUSY);
            g2.drawString("C-" + owner, lx, ly + 15);
        }
    }
}