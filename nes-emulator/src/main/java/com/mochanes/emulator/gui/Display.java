package com.mochanes.emulator.gui;

import com.mochanes.emulator.FrameSink;
import com.mochanes.emulator.Controller;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

public class Display extends JPanel implements FrameSink {

    /** Window title with no ROM loaded. */
    private static final String BASE_TITLE = "MochaNES";

    /**
     * Puts the running ROM in the title bar, document first, as most
     * applications do: "smb.nes - MochaNES".
     */
    public void setRomName(String name) {
        if (frame != null) {
            frame.setTitle(name == null || name.isEmpty() ? BASE_TITLE : name + " - " + BASE_TITLE);
        }
    }

    public static final int WIDTH = 256;
    public static final int HEIGHT = 240;

    private final BufferedImage image;
    private final int[] pixels;
    private Controller controller;
    private JFrame frame;
    private final boolean headless;
    private final Settings settings = new Settings();
    private Gamepad gamepad;

    // Menu items kept so the hotkeys and the menu stay in agreement.
    private JMenuBar menuBar;
    private JCheckBoxMenuItem crtToggleItem;
    private final java.util.Map<CrtFilter.Preset, JRadioButtonMenuItem> presetItems = new java.util.EnumMap<>(
            CrtFilter.Preset.class);
    private final java.util.Map<CrtFilter.Mask, JRadioButtonMenuItem> maskItems = new java.util.EnumMap<>(
            CrtFilter.Mask.class);

    /** Called when the user picks a ROM from the File menu. */
    private java.util.function.Consumer<java.io.File> romLoadHandler;
    /** Called when the user chooses File > Reset. */
    private Runnable resetHandler;
    private Runnable debuggerHandler;

    public void setRomLoadHandler(java.util.function.Consumer<java.io.File> handler) {
        this.romLoadHandler = handler;
    }

    /** Called when the user asks for the debugger, which is hidden by default. */
    public void setDebuggerHandler(Runnable handler) {
        this.debuggerHandler = handler;
    }

    public void setResetHandler(Runnable handler) {
        this.resetHandler = handler;
    }

    public Display() {
        this(false);
    }

    public Display(boolean headless) {
        this.headless = headless;
        if (!headless) {
            // Create a Window
            frame = new JFrame(BASE_TITLE);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.setLayout(new BorderLayout());
            frame.add(this, BorderLayout.CENTER);
            frame.setJMenuBar(buildMenuBar());
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            // Input Handling
            this.setFocusable(true);
            this.requestFocusInWindow();
            this.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER && e.isAltDown()) {
                        toggleFullscreen();
                        return;
                    }
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE && fullscreen) {
                        toggleFullscreen();
                        return;
                    }
                    if (handleDisplayKey(e.getKeyCode(), e.isShiftDown()))
                        return;
                    if (controller == null)
                        return;
                    updateController(e.getKeyCode(), true);
                }

                @Override
                public void keyReleased(KeyEvent e) {
                    if (controller == null)
                        return;
                    updateController(e.getKeyCode(), false);
                }
            });
        }

        // Initialize Image Buffer
        image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        // Optional: start with the CRT simulation already on.
        // -Dmochanes.crt or -Dmochanes.crt=arcade
        String crtProp = System.getProperty("mochanes.crt");
        if (!headless && crtProp != null) {
            crt.enabled = true;
            if (!crtProp.isEmpty() && !crtProp.equalsIgnoreCase("true")) {
                for (CrtFilter.Preset p : CrtFilter.Preset.values()) {
                    if (p.name().equalsIgnoreCase(crtProp)) {
                        crt.setPreset(p);
                        break;
                    }
                }
            }
            syncMenu();
        }
    }

    public void setController(Controller controller) {
        this.controller = controller;
        if (!headless) {
            applyGamepadSetting();
        }
    }

    // === CRT simulation ===

    private final CrtFilter crt = new CrtFilter();
    private BufferedImage crtImage;
    private int[] crtPixels;
    private String toast;
    private long toastUntil;

    public CrtFilter getCrtFilter() {
        return crt;
    }

    // === Menu ===

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        menuBar = bar;

        // --- File ---
        JMenu file = new JMenu("File");
        file.setMnemonic('F');

        JMenuItem open = new JMenuItem("Open ROM...");
        open.addActionListener(e -> chooseRom());
        file.add(open);

        JMenuItem reset = new JMenuItem("Reset");
        reset.addActionListener(e -> {
            if (resetHandler != null) {
                resetHandler.run();
                showToast("Reset");
            }
            refocus();
        });
        file.add(reset);

        file.addSeparator();
        JMenuItem debugger = new JMenuItem("Debugger");
        debugger.addActionListener(e -> {
            if (debuggerHandler != null) {
                debuggerHandler.run();
            }
            refocus();
        });
        file.add(debugger);

        file.addSeparator();
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> System.exit(0));
        file.add(exit);
        bar.add(file);

        // --- Video ---
        // Note: the hotkeys are handled by the panel's KeyListener, so these
        // items deliberately carry no accelerators - a matching accelerator
        // would fire alongside the KeyListener and toggle twice.
        JMenu video = new JMenu("Video");
        video.setMnemonic('V');

        JMenu scaleMenu = new JMenu("Window Size");
        ButtonGroup scaleGroup = new ButtonGroup();
        for (int m = 1; m <= 6; m++) {
            final int mult = m;
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(
                    mult + "x  (" + (WIDTH * mult) + "x" + (HEIGHT * mult) + ")", settings.scale == mult);
            it.addActionListener(e -> setScale(mult));
            scaleGroup.add(it);
            scaleMenu.add(it);
        }
        video.add(scaleMenu);

        JMenu aspectMenu = new JMenu("Aspect Ratio");
        ButtonGroup aspectGroup = new ButtonGroup();
        for (Settings.Aspect asp : Settings.Aspect.values()) {
            JRadioButtonMenuItem it = new JRadioButtonMenuItem(asp.label(), settings.aspect == asp);
            it.addActionListener(e -> setAspect(asp));
            aspectGroup.add(it);
            aspectMenu.add(it);
        }
        video.add(aspectMenu);

        JMenuItem fs = new JMenuItem("Fullscreen  (Alt+Enter)");
        fs.addActionListener(e -> toggleFullscreen());
        video.add(fs);
        video.addSeparator();

        crtToggleItem = new JCheckBoxMenuItem("CRT Simulation  (F1)", crt.enabled);
        crtToggleItem.addActionListener(e -> {
            crt.enabled = crtToggleItem.isSelected();
            showToast(crt.enabled ? crt.status() : "CRT: off");
            refocus();
        });
        video.add(crtToggleItem);

        JMenu presets = new JMenu("Preset  (F2)");
        ButtonGroup presetGroup = new ButtonGroup();
        for (CrtFilter.Preset p : CrtFilter.Preset.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(p.label(), crt.preset == p);
            item.addActionListener(e -> {
                crt.setPreset(p);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                refocus();
            });
            presetGroup.add(item);
            presets.add(item);
            presetItems.put(p, item);
        }
        video.add(presets);

        JMenu masks = new JMenu("Shadow Mask  (F3)");
        ButtonGroup maskGroup = new ButtonGroup();
        for (CrtFilter.Mask m : CrtFilter.Mask.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(maskLabel(m), crt.mask == m);
            item.addActionListener(e -> {
                crt.setMask(m);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                refocus();
            });
            maskGroup.add(item);
            masks.add(item);
            maskItems.put(m, item);
        }
        video.add(masks);

        video.addSeparator();
        video.add(adjustItem("Curvature  +  (F4)", () -> crt.adjustCurvature(0.03f)));
        video.add(adjustItem("Curvature  -  (F5)", () -> crt.adjustCurvature(-0.03f)));
        video.add(adjustItem("Tilt  Left  (F6)", () -> crt.adjustTilt(-0.05f)));
        video.add(adjustItem("Tilt  Right  (F7)", () -> crt.adjustTilt(0.05f)));
        video.add(adjustItem("Reset Geometry (flat)", crt::resetGeometry));

        video.addSeparator();
        video.add(adjustItem("Mask Strength  +  (F9)", () -> crt.adjustMaskStrength(0.04f)));
        video.add(adjustItem("Mask Strength  -  (F8)", () -> crt.adjustMaskStrength(-0.04f)));
        video.add(adjustItem("Bloom  +  (F11)", () -> crt.adjustBloom(0.04f)));
        video.add(adjustItem("Bloom  -  (F10)", () -> crt.adjustBloom(-0.04f)));
        video.add(adjustItem("Focus  sharper  (F12)", () -> crt.adjustSharpness(0.06f)));
        video.add(adjustItem("Focus  softer  (Shift+F12)", () -> crt.adjustSharpness(-0.06f)));
        bar.add(video);

        // --- Input ---
        JMenu input = new JMenu("Input");
        input.setMnemonic('I');
        JMenuItem rebind = new JMenuItem("Configure Buttons...");
        rebind.addActionListener(e -> showBindingDialog());
        input.add(rebind);
        JMenuItem defaults = new JMenuItem("Reset to Defaults");
        defaults.addActionListener(e -> {
            settings.resetBindings();
            settings.save();
            showToast("Bindings reset");
            refocus();
        });
        input.add(defaults);
        input.addSeparator();
        JCheckBoxMenuItem pad = new JCheckBoxMenuItem("Gamepad", settings.gamepadEnabled);
        pad.addActionListener(e -> {
            settings.gamepadEnabled = pad.isSelected();
            settings.save();
            applyGamepadSetting();
            refocus();
        });
        input.add(pad);
        bar.add(input);

        // --- Help ---
        JMenu help = new JMenu("Help");
        help.setMnemonic('H');
        JMenuItem controls = new JMenuItem("Controls...");
        controls.addActionListener(e -> showControls());
        help.add(controls);
        bar.add(help);

        return bar;
    }

    private JMenuItem adjustItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> {
            action.run();
            crt.enabled = true;
            syncMenu();
            showToast(crt.status());
            refocus();
        });
        return item;
    }

    private static String maskLabel(CrtFilter.Mask m) {
        switch (m) {
            case NONE:
                return "None";
            case APERTURE_GRILLE:
                return "Aperture Grille";
            case SHADOW_MASK:
                return "Shadow Mask";
            case SLOT_MASK:
                return "Slot Mask";
            default:
                return m.name();
        }
    }

    /** Pushes current filter state back into the menu widgets. */
    private void syncMenu() {
        if (crtToggleItem == null)
            return;
        crtToggleItem.setSelected(crt.enabled);
        JRadioButtonMenuItem p = presetItems.get(crt.preset);
        if (p != null)
            p.setSelected(true);
        JRadioButtonMenuItem m = maskItems.get(crt.mask);
        if (m != null)
            m.setSelected(true);
    }

    // === Window / scaling ===

    private boolean fullscreen = false;
    private Rectangle windowedBounds;

    public void setScale(int scale) {
        settings.scale = Math.max(1, Math.min(6, scale));
        settings.save();
        if (frame != null && !fullscreen) {
            frame.pack();
            frame.setLocationRelativeTo(null);
        }
        refocus();
    }

    public void setAspect(Settings.Aspect a) {
        settings.aspect = a;
        settings.save();
        if (frame != null && !fullscreen) {
            frame.pack();
        }
        refocus();
    }

    /**
     * Toggles borderless fullscreen. Undecorating the existing frame is more
     * reliable across window managers than exclusive fullscreen mode, and keeps
     * the letterboxing under our control.
     */
    public void toggleFullscreen() {
        if (frame == null) {
            return;
        }
        fullscreen = !fullscreen;
        frame.dispose(); // required before changing decoration
        if (fullscreen) {
            windowedBounds = frame.getBounds();
            frame.setJMenuBar(null);
            frame.setUndecorated(true);
            frame.setResizable(true);
            frame.setBounds(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration().getBounds());
        } else {
            frame.setUndecorated(false);
            frame.setJMenuBar(menuBar);
            frame.setResizable(false);
            if (windowedBounds != null) {
                frame.setBounds(windowedBounds);
            } else {
                frame.pack();
            }
        }
        frame.setVisible(true);
        refocus();
        showToast(fullscreen ? "Fullscreen  (Alt+Enter to exit)" : "Windowed");
    }

    /** Menus steal keyboard focus; hand it back so the game stays playable. */
    private void refocus() {
        requestFocusInWindow();
        repaint();
    }

    private void chooseRom() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open ROM");
        java.io.File romsDir = new java.io.File("roms");
        if (!romsDir.isDirectory())
            romsDir = new java.io.File("../roms");
        chooser.setCurrentDirectory(romsDir.isDirectory() ? romsDir : new java.io.File("."));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("NES ROMs (*.nes)", "nes"));

        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            java.io.File chosen = chooser.getSelectedFile();
            if (romLoadHandler != null) {
                try {
                    romLoadHandler.accept(chosen);
                    showToast("Loaded " + chosen.getName());
                } catch (RuntimeException ex) {
                    JOptionPane.showMessageDialog(frame,
                            "Could not load " + chosen.getName() + "\n" + ex.getMessage(),
                            "Load failed", JOptionPane.ERROR_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(frame,
                        "This build has no ROM loader attached.",
                        "Unavailable", JOptionPane.WARNING_MESSAGE);
            }
        }
        refocus();
    }

    /** Starts or stops the pad reader to match the current setting. */
    private void applyGamepadSetting() {
        if (settings.gamepadEnabled) {
            if (gamepad == null && controller != null) {
                gamepad = Gamepad.start(controller);
                showToast(gamepad != null
                        ? "Gamepad: " + gamepad.deviceName()
                        : "Gamepad: none detected");
            }
        } else if (gamepad != null) {
            gamepad.stop();
            gamepad = null;
            showToast("Gamepad: off");
        }
    }

    /**
     * Modal rebinding table. Click a button row, then press the key to assign;
     * a key already in use is released from its previous button.
     */
    private void showBindingDialog() {
        JPanel panel = new JPanel(new GridLayout(Settings.BUTTON_NAMES.length + 1, 2, 6, 6));
        JButton[] fields = new JButton[Settings.BUTTON_NAMES.length];

        for (int i = 0; i < Settings.BUTTON_NAMES.length; i++) {
            final int button = i;
            panel.add(new JLabel(Settings.BUTTON_NAMES[i] + " :", SwingConstants.RIGHT));
            JButton b = new JButton(KeyEvent.getKeyText(settings.keyFor(i)));
            b.setFocusable(true);
            b.addActionListener(e -> {
                b.setText("press a key...");
                b.requestFocusInWindow();
            });
            b.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    settings.bind(button, e.getKeyCode());
                    // Another row may have lost its key; refresh them all.
                    for (int k = 0; k < fields.length; k++) {
                        int code = settings.keyFor(k);
                        fields[k].setText(code < 0 ? "(unbound)" : KeyEvent.getKeyText(code));
                    }
                    e.consume();
                }
            });
            fields[i] = b;
            panel.add(b);
        }

        panel.add(new JLabel(""));
        JLabel hint = new JLabel("click a button, then press a key");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 11f));
        panel.add(hint);

        JOptionPane.showMessageDialog(frame, panel, "Configure Buttons", JOptionPane.PLAIN_MESSAGE);
        settings.save();
        showToast("Bindings saved");
        refocus();
    }

    private void showControls() {
        JOptionPane.showMessageDialog(frame,
                "Gamepad\n"
                        + "  D-Pad ......... Arrow keys\n"
                        + "  A / B ......... Z / X\n"
                        + "  Select ........ Shift\n"
                        + "  Start ......... Enter\n\n"
                        + "Window\n"
                        + "  Alt+Enter ..... Fullscreen (Esc to exit)\n\n"
                        + "Video\n"
                        + "  F1 ............ Toggle CRT simulation\n"
                        + "  F2 ............ Cycle preset\n"
                        + "  F3 ............ Cycle shadow mask\n"
                        + "  F4 / F5 ....... Curvature up / down\n"
                        + "  F6 / F7 ....... Tilt left / right\n"
                        + "  F8 / F9 ....... Mask strength down / up\n"
                        + "  F10 / F11 ..... Bloom down / up\n"
                        + "  F12 ........... Focus sharper (Shift+F12 softer)",
                "Controls", JOptionPane.INFORMATION_MESSAGE);
        refocus();
    }

    /** Handles display-level hotkeys. Returns true if the key was consumed. */
    private boolean handleDisplayKey(int keyCode, boolean shiftDown) {
        switch (keyCode) {
            case KeyEvent.VK_F1:
                crt.toggle();
                syncMenu();
                showToast(crt.enabled ? crt.status() : "CRT: off");
                return true;
            case KeyEvent.VK_F2:
                crt.cyclePreset();
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F3:
                crt.cycleMask();
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F4:
                crt.adjustCurvature(0.03f);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F5:
                crt.adjustCurvature(-0.03f);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F6:
                crt.adjustTilt(-0.05f);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F7:
                crt.adjustTilt(0.05f);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F8:
                crt.adjustMaskStrength(-0.04f);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F9:
                crt.adjustMaskStrength(0.04f);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F10:
                crt.adjustBloom(-0.04f);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F11:
                crt.adjustBloom(0.04f);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            case KeyEvent.VK_F12:
                // Sharpen; with Shift held, soften.
                crt.adjustSharpness(shiftDown ? -0.06f : 0.06f);
                crt.enabled = true;
                syncMenu();
                showToast(crt.status());
                return true;
            default:
                return false;
        }
    }

    private void showToast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 2000;
        repaint();
    }

    private void updateController(int keyCode, boolean pressed) {
        int button = settings.buttonForKey(keyCode);
        if (button >= 0) {
            controller.setButtonPressed(button, pressed);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        if (settings.aspect == Settings.Aspect.ASPECT_4_3) {
            return new Dimension(HEIGHT * settings.scale * 4 / 3, HEIGHT * settings.scale);
        }
        return new Dimension(WIDTH * settings.scale, HEIGHT * settings.scale);
    }

    private String overlayText;

    public void setOverlayText(String text) {
        this.overlayText = text;
        repaint();
    }

    /**
     * Where the 256x240 picture lands inside the component, honouring the
     * chosen aspect mode. Anything outside is letterboxed.
     */
    private Rectangle viewport() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return new Rectangle(0, 0, 1, 1);
        }
        switch (settings.aspect) {
            case STRETCH:
                return new Rectangle(0, 0, w, h);

            case ASPECT_4_3: {
                // The NES has non-square pixels; on a TV the 256x240 frame fills
                // a 4:3 window.
                int dw = w, dh = (int) Math.round(w * 3.0 / 4.0);
                if (dh > h) {
                    dh = h;
                    dw = (int) Math.round(h * 4.0 / 3.0);
                }
                return new Rectangle((w - dw) / 2, (h - dh) / 2, dw, dh);
            }

            case PIXEL_PERFECT:
            default: {
                // Largest whole-pixel multiple that fits; never below 1x.
                int mult = Math.max(1, Math.min(w / WIDTH, h / HEIGHT));
                int dw = WIDTH * mult, dh = HEIGHT * mult;
                return new Rectangle((w - dw) / 2, (h - dh) / 2, dw, dh);
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Rectangle vp = viewport();
        if (vp.width != getWidth() || vp.height != getHeight()) {
            g.setColor(Color.BLACK); // letterbox
            g.fillRect(0, 0, getWidth(), getHeight());
        }

        // Hold the frame lock so refresh() can't swap in a new frame midway
        // through drawing this one.
        synchronized (frameLock) {
            if (crt.enabled) {
                drawCrt(g, vp);
            } else {
                g.drawImage(image, vp.x, vp.y, vp.width, vp.height, null);
            }
        }

        // Draw Overlay Text
        if (overlayText != null && !overlayText.isEmpty()) {
            Graphics2D g2d = (Graphics2D) g;
            g2d.setColor(Color.RED);
            g2d.setFont(new Font("Arial", Font.BOLD, 24));
            g2d.drawString(overlayText, 20, 40);
        }

        // Transient status line for the CRT hotkeys.
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            Graphics2D g2d = (Graphics2D) g;
            g2d.setFont(new Font("SansSerif", Font.BOLD, 13));
            int tw = g2d.getFontMetrics().stringWidth(toast);
            g2d.setColor(new Color(0, 0, 0, 170));
            g2d.fillRect(10, getHeight() - 34, tw + 16, 24);
            g2d.setColor(new Color(120, 255, 160));
            g2d.drawString(toast, 18, getHeight() - 17);
        }
    }

    private void drawCrt(Graphics g, Rectangle vp) {
        int w = vp.width, h = vp.height;
        if (w <= 0 || h <= 0)
            return;

        int[] out = crt.process(pixels, WIDTH, HEIGHT, w, h);

        if (crtImage == null || crtImage.getWidth() != w || crtImage.getHeight() != h) {
            crtImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            crtPixels = ((DataBufferInt) crtImage.getRaster().getDataBuffer()).getData();
        }
        System.arraycopy(out, 0, crtPixels, 0, Math.min(out.length, crtPixels.length));
        g.drawImage(crtImage, vp.x, vp.y, null);
    }

    // Double buffering. The emulation thread draws into renderBuffer while the
    // EDT paints from `pixels`; without the split, the EDT could paint a frame
    // the PPU was still halfway through drawing, which shows up as tearing.
    private final int[] renderBuffer = new int[WIDTH * HEIGHT];
    private final Object frameLock = new Object();

    @Override
    public void setPixel(int x, int y, int color) {
        if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) {
            // Headless has no EDT painting concurrently, so it can write
            // straight to the visible buffer and skip the per-frame copy.
            (headless ? pixels : renderBuffer)[y * WIDTH + x] = color;
        }
    }

    /** The completed frame, safe to read (screenshots, filters). */
    public int[] getPixels() {
        return pixels;
    }

    /** Called by the PPU at VBlank, once the frame is complete. */
    @Override
    public void refresh() {
        if (headless) {
            return; // nothing to present
        }
        synchronized (frameLock) {
            System.arraycopy(renderBuffer, 0, pixels, 0, renderBuffer.length);
        }
        repaint();
    }
}
