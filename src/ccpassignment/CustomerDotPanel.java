package ccpassignment;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 *
 * @author Xenia Thor
 */

// * Renders 50 customer dots in a compact grid, each coloured
// * by the customer's current state.
// * Colours match the legend in the lecturer's example video:
// *   • Grey    = Arrived
// *   • Yellow  = Basket
// *   • Blue    = Washing
// *   • Orange  = Drying
// *   • Purple  = Payment
// *   • Green   = Done
// *   • Red     = Left (impatient)
public class CustomerDotPanel extends JPanel {

    // State → colour mapping
    private static final Color[] STATE_COLORS = {
        new Color(180, 175, 190),  // ARRIVED   — grey
        new Color(255, 235, 133),  // BASKET    — yellow
        new Color(120, 129, 255),  // WASHING   — blue
        new Color(255, 157, 38),  // DRYING    — orange
        new Color(160,  90, 220),  // PAYMENT   — purple
        new Color(84, 255, 107),  // DONE      — green
        new Color(252, 8, 73),  // LEFT      — red
    };

    private static final String[] STATE_LABELS = {
        "Arrived", "Basket", "Washing", "Drying", "Payment", "Done", "Left"
    };

    public CustomerDotPanel() {
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);

        List<SmartLaundrySimulation.CustomerState> states = new ArrayList<>();
        synchronized (SmartLaundrySimulation.customers) {
            for (CustomerThread c : SmartLaundrySimulation.customers) {
                states.add(c.CustomerState);
            }
        }

        if (states.isEmpty()) {
            g2.setFont(new Font("Times New Roman", Font.ITALIC, 11));
            g2.setColor(new Color(120, 110, 140));
            g2.drawString("Waiting for simulation to start...", 10, getHeight() / 2 + 4);
            drawLegend(g2, 10, getHeight() - 16);
            return;
        }

        int dot = 16, gap = 3;
        int W = getWidth();
        int perRow = Math.max(1, (W - 10) / (dot + gap));

        int col = 0, row = 0;
        for (int i = 0; i < states.size(); i++) {
            SmartLaundrySimulation.CustomerState state = states.get(i);
            int stateIdx = state.ordinal();
            Color c = STATE_COLORS[Math.min(stateIdx, STATE_COLORS.length - 1)];

            int px = 6 + col * (dot + gap);
            int py = 4 + row * (dot + gap + 1);

            // Draw dot
            g2.setColor(c);
            g2.fillOval(px, py, dot, dot);

            // Subtle border
            g2.setColor(c.darker());
            g2.setStroke(new BasicStroke(0.8f));
            g2.drawOval(px, py, dot, dot);

            // Customer number inside dot
            g2.setFont(new Font("Times New Roman", Font.BOLD, 7));
            g2.setColor(Color.WHITE);
            String num = String.valueOf(i + 1);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(num,
                px + (dot - fm.stringWidth(num)) / 2,
                py + dot / 2 + fm.getAscent() / 2 - 1);

            col++;
            if (col >= perRow) { col = 0; row++; }
        }

        // Legend row at bottom
        drawLegend(g2, 6, getHeight() - 14);
    }

    private void drawLegend(Graphics2D g2, int x, int y) {
        g2.setFont(new Font("Times New Roman", Font.PLAIN, 9));
        FontMetrics fm = g2.getFontMetrics();
        int px = x;
        for (int i = 0; i < STATE_LABELS.length; i++) {
            g2.setColor(STATE_COLORS[i]);
            g2.fillOval(px, y - 8, 9, 9);
            g2.setColor(new Color(50, 40, 70));
            g2.drawString(STATE_LABELS[i], px + 12, y);
            px += fm.stringWidth(STATE_LABELS[i]) + 22;
        }
    }
}
