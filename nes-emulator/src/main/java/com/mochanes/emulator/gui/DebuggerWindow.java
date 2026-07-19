package com.mochanes.emulator.gui;

import com.mochanes.emulator.CPU;
import com.mochanes.emulator.Disassembler;
import com.mochanes.emulator.EmulatorRunner;
import com.mochanes.emulator.NES;
import com.mochanes.emulator.PPU;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public class DebuggerWindow extends JFrame {
    private final NES nes;
    private final EmulatorRunner runner;
    private final Disassembler disassembler;
    private Timer refreshTimer;

    private JTextArea cpuStateArea;
    private JTable memoryTable;
    private MemoryTableModel memoryModel;
    private JTextArea disassemblyArea;
    private JPanel chrPanel;
    private BufferedImage[] chrImages;
    private int selectedPalette = 0;

    public DebuggerWindow(NES nes, EmulatorRunner runner) {
        this.nes = nes;
        this.runner = runner;
        this.disassembler = new Disassembler(nes);

        setTitle("NES Debugger");
        setSize(900, 700);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Controls", createControlsPanel());
        tabs.addTab("Disassembly", createDisassemblyPanel());
        tabs.addTab("Memory", createMemoryPanel());
        tabs.addTab("CHR-ROM", createChrPanel());

        add(tabs, BorderLayout.CENTER);

        // Timer to refresh UI
        refreshTimer = new Timer(20, e -> refreshUI()); // 50Hz
        refreshTimer.start();
    }

    private JPanel createControlsPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        // Buttons
        JPanel buttons = new JPanel();
        JButton playBtn = new JButton("Run");
        playBtn.addActionListener(e -> resume()); // FIXED: Use resume helper

        JButton pauseBtn = new JButton("Pause");
        pauseBtn.addActionListener(e -> runner.setPaused(true));

        JButton stepBtn = new JButton("Step");
        stepBtn.addActionListener(e -> runner.step());

        buttons.add(playBtn);
        buttons.add(pauseBtn);
        buttons.add(stepBtn);

        panel.add(buttons, BorderLayout.NORTH);

        // CPU State
        cpuStateArea = new JTextArea();
        cpuStateArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        cpuStateArea.setEditable(false);
        panel.add(new JScrollPane(cpuStateArea), BorderLayout.CENTER);

        return panel;
    }

    private JPanel createDisassemblyPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        disassemblyArea = new JTextArea();
        disassemblyArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        disassemblyArea.setEditable(false);
        panel.add(new JScrollPane(disassemblyArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createMemoryPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        memoryModel = new MemoryTableModel();
        memoryTable = new JTable(memoryModel);
        memoryTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
        memoryTable.setDefaultRenderer(Object.class, new MemoryCellRenderer());
        memoryTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // Widths
        memoryTable.getColumnModel().getColumn(0).setPreferredWidth(60); // Addr
        for (int i = 1; i <= 16; i++)
            memoryTable.getColumnModel().getColumn(i).setPreferredWidth(30);

        panel.add(new JScrollPane(memoryTable), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createChrPanel() {
        chrPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (chrImages != null) {
                    g.drawImage(chrImages[0], 10, 10, 256, 256, null); // Scale 2x
                    g.drawImage(chrImages[1], 280, 10, 256, 256, null); // Scale 2x
                }
            }
        };
        chrPanel.setPreferredSize(new Dimension(550, 300));

        JPanel container = new JPanel(new BorderLayout());
        container.add(chrPanel, BorderLayout.CENTER);

        JButton palBtn = new JButton("Cycle Palette");
        palBtn.addActionListener(e -> {
            selectedPalette = (selectedPalette + 1) % 8;
        });
        container.add(palBtn, BorderLayout.SOUTH);

        return container;
    }

    private void refreshUI() {
        if (!isVisible())
            return;

        // CPU
        CPU cpu = nes.getCpu();
        if (cpu != null) {
            String state = String.format(
                    "PC:  $%04X\n" +
                            "A:   $%02X\n" +
                            "X:   $%02X\n" +
                            "Y:   $%02X\n" +
                            "SP:  $%02X\n" +
                            "NV-BDIZC\n" +
                            "%8s\n" +
                            "Cycles: %d",
                    cpu.getPC(),
                    cpu.getReg(2), // A
                    cpu.getReg(0), // X
                    cpu.getReg(1), // Y
                    cpu.getSP(),
                    Integer.toBinaryString(cpu.getFlags() | 0x100).substring(1),
                    cpu.getTotalCycles());
            cpuStateArea.setText(state);

            // Disassembly
            StringBuilder sb = new StringBuilder();
            int pc = cpu.getPC();
            for (int i = 0; i < 25; i++) { // Show next 25 instructions
                sb.append(disassembler.disassemble(pc)).append("\n");
                pc += disassembler.getInstructionLength(pc);
            }
            disassemblyArea.setText(sb.toString());
        }

        // Memory Table - Snapshot logic
        // We update the 'prev' buffer AFTER rendering so we detect changes from last
        // frame
        memoryModel.updateSnapshot();
        memoryTable.repaint();

        // PPU CHR
        PPU ppu = nes.getPpu();
        if (ppu != null) {
            chrImages = new BufferedImage[2];
            for (int i = 0; i < 2; i++) {
                int[] pixels = ppu.getPatternTable(i, selectedPalette);
                BufferedImage img = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
                int[] buffer = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
                System.arraycopy(pixels, 0, buffer, 0, pixels.length);
                chrImages[i] = img;
            }
            chrPanel.repaint();
        }
    }

    // Resume helper
    private void resume() {
        if (runner.isPaused()) {
            runner.setPaused(false);
        } else {
            runner.start();
        }
    }

    // === Inner Classes for Memory Table ===

    private class MemoryTableModel extends AbstractTableModel {
        private final byte[] currentMemory = new byte[65536];
        private final long[] lastChangeTime = new long[65536]; // Track timestamp of modification

        public void updateSnapshot() {
            long now = System.currentTimeMillis();
            boolean changed = false;

            for (int i = 0; i < 65536; i++) {
                byte newVal;
                // Skip PPU registers safety logic
                if (i >= 0x2000 && i <= 0x401F) {
                    newVal = 0;
                } else {
                    newVal = (byte) nes.getMemory().peek(i);
                }

                if (newVal != currentMemory[i]) {
                    currentMemory[i] = newVal;
                    lastChangeTime[i] = now;
                    changed = true;
                }
            }
            if (changed) {
                // fireTableDataChanged(); // Don't fire full update, just let repaint handle it
                // to avoid resetting selection
            }
        }

        public long getLastChangeTime(int addr) {
            return lastChangeTime[addr];
        }

        @Override
        public int getRowCount() {
            return 4096;
        }

        @Override
        public int getColumnCount() {
            return 17;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (columnIndex == 0)
                return String.format("$%04X", rowIndex * 16);
            int addr = rowIndex * 16 + (columnIndex - 1);
            return String.format("%02X", currentMemory[addr]);
        }

        @Override
        public String getColumnName(int column) {
            if (column == 0)
                return "Addr";
            return String.format("%02X", column - 1);
        }
    }

    private class MemoryCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            if (column > 0) {
                int addr = row * 16 + (column - 1);
                long lastChange = memoryModel.getLastChangeTime(addr);
                long age = System.currentTimeMillis() - lastChange;

                if (age < 2000) { // Fade over 2 seconds
                    float ratio = age / 2000f; // 0.0 (New) -> 1.0 (Old)
                    // Interpolate Red -> White
                    // Red: (255, 0, 0)
                    // White: (255, 255, 255)
                    // G/B go from 0 -> 255
                    int gb = (int) (255 * ratio);
                    c.setBackground(new Color(255, gb, gb));
                } else {
                    c.setBackground(Color.WHITE);
                }
            } else {
                c.setBackground(Color.LIGHT_GRAY);
            }

            if (isSelected) {
                c.setBackground(c.getBackground().darker());
            }

            return c;
        }
    }
}
