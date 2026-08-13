package com.mochanes.emulator.gui;

import com.mochanes.emulator.Memory;

import javax.swing.*;
import java.awt.*;

public class MemoryViewer {
    private final Memory memory;
    private JTextArea textArea;
    private JScrollPane scrollPane;
    private final byte[] previousMemoryState = new byte[0x10000]; // Store previous memory state

    public MemoryViewer(Memory memory) {
        this.memory = memory;
    }

    public void displayMemory() {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("MochaNES Memory Viewer");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(600, 600);

            textArea = new JTextArea();
            textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
            textArea.setEditable(false);

            scrollPane = new JScrollPane(textArea);
            scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
            scrollPane.getVerticalScrollBar().setUnitIncrement(16); // Smooth scrolling

            frame.add(scrollPane);
            frame.setVisible(true);

            updateMemory(); // Populate initial memory state
        });
    }

    public void updateMemory() {
        if (textArea == null)
            return;

        SwingUtilities.invokeLater(() -> {
            JScrollBar verticalScrollBar = scrollPane.getVerticalScrollBar();
            int scrollValue = verticalScrollBar.getValue();
            boolean atBottom = (scrollValue + scrollPane.getViewport().getHeight()) >= (verticalScrollBar.getMaximum()
                    - 20);

            StringBuilder sb = new StringBuilder();
            int bytesPerRow = 16;

            for (int addr = 0x0000; addr < 0x10000; addr += bytesPerRow) {
                // Address section
                sb.append(String.format("%04X: ", addr));

                for (int i = 0; i < bytesPerRow; i++) {
                    int effectiveAddr = addr + i;

                    int value = memory.peek(effectiveAddr);
                    boolean changed = previousMemoryState[effectiveAddr] != (byte) value;
                    previousMemoryState[effectiveAddr] = (byte) value;

                    // If changed, add a marker (*)
                    if (changed) {
                        sb.append(String.format("*%02X* ", value)); // Star marks modified bytes
                    } else {
                        sb.append(String.format("%02X ", value));
                    }
                }

                sb.append(" | ");

                for (int i = 0; i < bytesPerRow; i++) {
                    int effectiveAddr = addr + i;

                    int value = memory.peek(effectiveAddr);
                    char ascii = (value >= 32 && value <= 126) ? (char) value : '.';
                    sb.append(ascii);
                }

                sb.append("\n");
            }

            textArea.setText(sb.toString()); // Efficiently update text content

            // Restore scroll position
            SwingUtilities.invokeLater(() -> {
                if (!atBottom) {
                    verticalScrollBar.setValue(scrollValue);
                }
            });
        });
    }
}
