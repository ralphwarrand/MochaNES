package com.mochanes.ai;

import java.util.ArrayList;
import java.util.List;

public class ValidActions {

    // Standard valid masks
    // 0: A, 1: B, 2: Select, 3: Start, 4: Up, 5: Down, 6: Left, 7: Right

    // We pre-generate a list of "Human Feasible" actions.
    private static final List<Integer> LEGAL_ACTIONS = new ArrayList<>();

    // Configuration Flags
    private static boolean ALLOW_DIAGONALS = false;
    private static boolean ALLOW_SELECT_COMBOS = false;
    private static boolean ALLOW_START_COMBOS = false;
    private static boolean ALLOW_SELECT_START = false; // Specific check for Select+Start
    private static boolean ALLOW_OPPOSITE_DIR = false;

    // New Flags for disabling specific buttons
    private static boolean ALLOW_START = true;
    private static boolean ALLOW_SELECT = true;

    // Fast lookup for "Config-Allowed" actions (ignoring max buttons)
    private static final boolean[] CONFIG_ALLOWED_CACHE = new boolean[256];

    static {
        // Default Initialization
        rebuildCache();
    }

    public static void configure(java.util.Properties p) {
        ALLOW_DIAGONALS = Boolean.parseBoolean(p.getProperty("action_allow_diagonals", "false"));
        ALLOW_SELECT_COMBOS = Boolean.parseBoolean(p.getProperty("action_allow_select_combos", "false"));
        ALLOW_START_COMBOS = Boolean.parseBoolean(p.getProperty("action_allow_start_combos", "false"));
        ALLOW_SELECT_START = Boolean.parseBoolean(p.getProperty("action_allow_select_start", "false"));

        ALLOW_START = Boolean.parseBoolean(p.getProperty("action_allow_start", "true"));
        ALLOW_SELECT = Boolean.parseBoolean(p.getProperty("action_allow_select", "true"));

        System.out.println("Action Space Configured: Diagonals=" + ALLOW_DIAGONALS +
                ", SelCombos=" + ALLOW_SELECT_COMBOS + ", StartCombos=" + ALLOW_START_COMBOS +
                ", SelStart=" + ALLOW_SELECT_START);

        rebuildCache();
    }

    private static void rebuildCache() {
        LEGAL_ACTIONS.clear();
        for (int i = 0; i < 256; i++) {
            boolean valid = checkRules(i);
            CONFIG_ALLOWED_CACHE[i] = valid;
            if (valid) {
                LEGAL_ACTIONS.add(i); // Note: This ignores max_simultaneous, purely config rules
            }
        }
    }

    private static boolean checkRules(int mask) {
        if (mask == 0)
            return true;

        // 1. Hardware Impossible
        boolean left = (mask & 64) != 0;
        boolean right = (mask & 128) != 0;
        boolean up = (mask & 16) != 0;
        boolean down = (mask & 32) != 0;

        if (!ALLOW_OPPOSITE_DIR) {
            if (left && right)
                return false;
            if (up && down)
                return false;
        }

        // 2. Diagonals
        boolean horiz = left || right;
        boolean vert = up || down;
        if (horiz && vert && !ALLOW_DIAGONALS)
            return false;

        // 3. Modifiers (Select/Start)
        boolean select = (mask & 4) != 0;
        boolean start = (mask & 8) != 0;

        if (start && !ALLOW_START)
            return false;
        if (select && !ALLOW_SELECT)
            return false;

        // Strict Select+Start check
        if (select && start && !ALLOW_SELECT_START)
            return false;

        boolean others = (mask & (1 | 2 | 16 | 32 | 64 | 128)) != 0;

        if (select && others && !ALLOW_SELECT_COMBOS)
            return false;
        if (start && others && !ALLOW_START_COMBOS)
            return false;

        return true;
    }

    public static List<Integer> getSafeActions() {
        return LEGAL_ACTIONS;
    }

    public static boolean isValid(int mask, int maxSimultaneous) {
        // 1. Check Config Rules (Cached)
        if (!CONFIG_ALLOWED_CACHE[mask & 0xFF])
            return false;

        // 2. Count Buttons
        int count = Integer.bitCount(mask);
        return count <= maxSimultaneous;
    }

    /**
     * Filters a raw list of actions (e.g. from Neural Net) by validity rules.
     */
    public static boolean isHumanFeasible(int mask, int maxSimultaneous) {
        return isValid(mask, maxSimultaneous);
    }

    /**
     * Returns a float mask for logits.
     * Valid actions = 0.0f
     * Invalid actions = -1e9f
     */
    public static float[] getActionMask(int maxSimultaneous) {
        float[] mask = new float[256];
        for (int i = 0; i < 256; i++) {
            if (isValid(i, maxSimultaneous)) {
                mask[i] = 0.0f;
            } else {
                mask[i] = -1e9f;
            }
        }
        return mask;
    }

    public static String getButtonName(int mask) {
        if (mask == 0)
            return "NO-OP";
        List<String> b = new ArrayList<>();
        if ((mask & 1) != 0)
            b.add("A");
        if ((mask & 2) != 0)
            b.add("B");
        if ((mask & 4) != 0)
            b.add("Sel");
        if ((mask & 8) != 0)
            b.add("Start");
        if ((mask & 16) != 0)
            b.add("Up");
        if ((mask & 32) != 0)
            b.add("Down");
        if ((mask & 64) != 0)
            b.add("Left");
        if ((mask & 128) != 0)
            b.add("Right");
        return String.join("+", b);
    }
}
