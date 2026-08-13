package com.mochanes.emulator.performance;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

public class PerformanceMonitor {
    private static JFrame frame;
    private static JTable table;
    private static DefaultTableModel model;
    private static volatile boolean running = false;

    public static void start() {
        if (running)
            return;
        running = true;
        Metrics.setEnabled(true);

        SwingUtilities.invokeLater(() -> {
            frame = new JFrame("MochaNES Performance");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setSize(600, 400);

            model = new DefaultTableModel(new Object[] { "Name", "Calls", "Avg (ms)", "Max (ms)", "Total (s)" }, 0);
            table = new JTable(model);
            frame.add(new JScrollPane(table));

            frame.setVisible(true);

            // Start Update Thread
            Thread updateThread = new Thread(() -> {
                while (frame.isVisible()) {
                    try {
                        Thread.sleep(1000); // Update every second
                        updateUI();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                running = false;
                Metrics.setEnabled(false);
            });
            updateThread.setDaemon(true);
            updateThread.start();
        });
    }

    private static void updateUI() {
        Map<String, Metrics.MetricStats> stats = Metrics.getInstance().snapshot();

        SwingUtilities.invokeLater(() -> {
            model.setRowCount(0);

            // Sort by Name to show hierarchy
            stats.values().stream()
                    .sorted((a, b) -> a.name.compareTo(b.name))
                    .forEach(s -> {
                        // Simple Indentation
                        String displayName = s.name;
                        int depth = displayName.length() - displayName.replace(".", "").length();
                        String indent = "  ".repeat(depth);

                        model.addRow(new Object[] {
                                indent + displayName,
                                s.totalCalls,
                                String.format("%.4f", s.avgTimeMs),
                                String.format("%.4f", s.maxTimeNano / 1_000_000.0),
                                String.format("%.2f", s.totalTimeNano / 1_000_000_000.0)
                        });
                    });
        });
    }
}
