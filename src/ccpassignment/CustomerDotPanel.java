package ccpassignment;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

/**
 *
 * @author Xenia Thor
 */

// Renders 50 customer dots in a compact grid, each coloured
// by the customer's current state.
// Colours match the legend in the lecturer's example video:
// • Grey = Arrived
// • Yellow = Basket
// • Blue = Washing
// • Orange = Drying
// • Purple = Payment
// • Green = Done
// • Red = Left (impatient)
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
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D graphics2 = (Graphics2D) graphics;
        graphics2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);

        List<SmartLaundrySimulation.CustomerState> states = new ArrayList<>();
        synchronized (SmartLaundrySimulation.customers) {
            for (CustomerThread c : SmartLaundrySimulation.customers) {
                states.add(c.customerState);
            }
        }

        if (states.isEmpty()) {
            graphics2.setFont(new Font("Times New Roman", Font.ITALIC, 11));
            graphics2.setColor(new Color(120, 110, 140));
            graphics2.drawString("Waiting for simulation to start...", 10, getHeight() / 2 + 4);
            drawLegend(graphics2, 10, getHeight() - 16);
            return;
        }

        int dot = 16, gap = 3;
        int W = getWidth();
        int perRow = Math.max(1, (W - 10) / (dot + gap));

        int col = 0, row = 0;
        for (int i = 0; i < states.size(); i++) {
            SmartLaundrySimulation.CustomerState state = states.get(i);
            int stateIdx = state.ordinal();
            Color color = STATE_COLORS[Math.min(stateIdx, STATE_COLORS.length - 1)];

            int px = 6 + col * (dot + gap);
            int py = 4 + row * (dot + gap + 1);

            // Draw dot
            graphics2.setColor(color);
            graphics2.fillOval(px, py, dot, dot);

            // Subtle border
            graphics2.setColor(color.darker());
            graphics2.setStroke(new BasicStroke(0.8f));
            graphics2.drawOval(px, py, dot, dot);

            // Customer number inside dot
            graphics2.setFont(new Font("Times New Roman", Font.BOLD, 7));
            graphics2.setColor(Color.WHITE);
            String num = String.valueOf(i + 1);
            FontMetrics fm = graphics2.getFontMetrics();
            graphics2.drawString(num,
                px + (dot - fm.stringWidth(num)) / 2,
                py + dot / 2 + fm.getAscent() / 2 - 1);

            col++;
            if (col >= perRow) { col = 0; row++; }
        }

        // Legend row at bottom
        drawLegend(graphics2, 6, getHeight() - 14);
    }

    private void drawLegend(Graphics2D graphics2, int x, int y) {
        graphics2.setFont(new Font("Times New Roman", Font.PLAIN, 9));
        FontMetrics fm = graphics2.getFontMetrics();
        int px = x;
        for (int i = 0; i < STATE_LABELS.length; i++) {
            graphics2.setColor(STATE_COLORS[i]);
            graphics2.fillOval(px, y - 8, 9, 9);
            graphics2.setColor(new Color(50, 40, 70));
            graphics2.drawString(STATE_LABELS[i], px + 12, y);
            px += fm.stringWidth(STATE_LABELS[i]) + 22;
        }
    }
}
