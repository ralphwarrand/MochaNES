package com.mochanes.ai.games.zelda;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class ZeldaWatcher extends JFrame {

    private final VideoPanel videoPanel;
    private final ControlPanel controlPanel;
    private final PlotPanel plotPanel;

    private final BufferedImage image;
    private final int width = 256;
    private final int height = 240;
    private final int scale = 2;

    public ZeldaWatcher() {
        super("Zelda Hyrule Mind - Watcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        videoPanel = new VideoPanel();
        
        JPanel sidePanel = new JPanel(new BorderLayout());
        controlPanel = new ControlPanel();
        plotPanel = new PlotPanel();
        
        sidePanel.add(controlPanel, BorderLayout.NORTH);
        sidePanel.add(plotPanel, BorderLayout.CENTER);

        add(videoPanel, BorderLayout.CENTER);
        add(sidePanel, BorderLayout.EAST);

        pack();
        setVisible(true);
    }

    public void update(int[] screenBuffer, ZeldaGameState state, float[] features, float reward, int action) {
        if (screenBuffer != null) {
            image.setRGB(0, 0, width, height, screenBuffer, 0, width);
        }
        videoPanel.currentState = state;
        videoPanel.currentFeatures = features;
        videoPanel.currentReward = reward;
        videoPanel.currentAction = action;
        videoPanel.repaint();
    }

    public void addMetrics(ZeldaAgent.TrainingMetrics m, float meanReward) {
        plotPanel.addMetrics(m, meanReward);
    }

    public int getRenderSkipFrames() {
        return controlPanel.renderSkipSlider.getValue();
    }

    private class VideoPanel extends JPanel {
        ZeldaGameState currentState;
        float[] currentFeatures;
        float currentReward;
        int currentAction;

        VideoPanel() {
            setPreferredSize(new Dimension(width * scale, height * scale));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.drawImage(image, 0, 0, width * scale, height * scale, null);
            if (currentState != null && currentFeatures != null && currentFeatures.length >= 12) {
                drawVectors(g);
                drawHUD(g);
            }
        }

        private void drawVectors(Graphics g) {
            float lx = currentFeatures[0] * width * scale;
            float ly = currentFeatures[1] * height * scale;

            g.setColor(Color.CYAN);
            g.fillOval((int) lx - 4, (int) ly - 4, 8, 8);

            int ptr = 12; // Start after 12 link/meta stats
            for (int i = 0; i < 6; i++) {
                if (ptr + 4 >= currentFeatures.length) break;
                float active = currentFeatures[ptr];
                float dx = currentFeatures[ptr + 1];
                float dy = currentFeatures[ptr + 2];
                ptr += 5;
                if (active > 0.5f) {
                    float ex = lx + (dx * width * scale);
                    float ey = ly + (dy * height * scale);
                    g.setColor(Color.RED);
                    g.drawLine((int) lx, (int) ly, (int) ex, (int) ey);
                    g.fillOval((int) ex - 4, (int) ey - 4, 8, 8);
                }
            }

            for (int i = 0; i < 4; i++) {
                if (ptr + 3 >= currentFeatures.length) break;
                float active = currentFeatures[ptr];
                float dx = currentFeatures[ptr + 1];
                float dy = currentFeatures[ptr + 2];
                ptr += 4;
                if (active > 0.5f) {
                    float px = lx + (dx * width * scale);
                    float py = ly + (dy * height * scale);
                    g.setColor(Color.ORANGE);
                    g.drawLine((int) lx, (int) ly, (int) px, (int) py);
                    g.drawRect((int) px - 3, (int) py - 3, 6, 6);
                }
            }
        }

        private void drawHUD(Graphics g) {
            g.setColor(Color.WHITE);
            g.setFont(new Font("Monospaced", Font.BOLD, 14));
            g.drawString("Reward: " + currentReward, 10, 20);
            g.drawString("Action: " + currentAction, 10, 40);

            float health = currentFeatures[6];
            g.setColor(Color.RED);
            g.fillRect(10, 50, (int) (health * 100), 10);
            g.setColor(Color.WHITE);
            g.drawRect(10, 50, 100, 10);
        }
    }

    private class ControlPanel extends JPanel {
        JSlider renderSkipSlider;

        ControlPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createTitledBorder("Hyperparameters"));
            
            JLabel skipLabel = new JLabel("Render Skip Frames");
            renderSkipSlider = new JSlider(1, 30, 4);
            renderSkipSlider.setPaintTicks(true);
            renderSkipSlider.setPaintLabels(true);
            renderSkipSlider.setMajorTickSpacing(10);
            
            add(skipLabel);
            add(renderSkipSlider);
        }
    }

    private class PlotPanel extends JPanel {
        List<Float> rewards = new ArrayList<>();
        List<Float> actorLoss = new ArrayList<>();

        PlotPanel() {
            setPreferredSize(new Dimension(300, 300));
            setBackground(Color.BLACK);
        }

        void addMetrics(ZeldaAgent.TrainingMetrics m, float meanRwd) {
            rewards.add(meanRwd);
            actorLoss.add(m.actorLoss);
            if (rewards.size() > 100) {
                rewards.remove(0);
                actorLoss.remove(0);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (rewards.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            drawGraph(g2, rewards, Color.GREEN, "Mean Reward", 20);
            drawGraph(g2, actorLoss, Color.RED, "Actor Loss", 150);
        }

        private void drawGraph(Graphics2D g, List<Float> data, Color c, String label, int yOffset) {
            g.setColor(c);
            g.drawString(label + ": " + data.get(data.size() - 1), 10, yOffset);
            
            float max = 0.001f;
            float min = -0.001f;
            for (float v : data) {
                if (v > max) max = v;
                if (v < min) min = v;
            }

            int w = getWidth();
            int h = 100; // Half height area
            float range = Math.max(0.01f, max - min);

            for (int i = 0; i < data.size() - 1; i++) {
                int x1 = (int) ((i / (float) 100) * w);
                int x2 = (int) (((i + 1) / (float) 100) * w);
                
                int y1 = yOffset + h - (int) (((data.get(i) - min) / range) * h);
                int y2 = yOffset + h - (int) (((data.get(i + 1) - min) / range) * h);
                
                g.drawLine(x1, y1, x2, y2);
            }
        }
    }
}
