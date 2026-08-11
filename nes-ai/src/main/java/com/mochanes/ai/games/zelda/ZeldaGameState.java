package com.mochanes.ai.games.zelda;

import com.mochanes.ai.HeadlessNES;

/**
 * Reads the raw state of "The Legend of Zelda" from NES RAM.
 * Encapsulates all magic numbers/addresses.
 */
public class ZeldaGameState {

    private final HeadlessNES nes;

    // RAM Addresses
    private static final int ADDR_LINK_X = 0x0070;
    private static final int ADDR_LINK_Y = 0x0084;
    private static final int ADDR_LINK_DIR = 0x0098;
    private static final int ADDR_LINK_STATE = 0x0012; // Game Mode/State
    private static final int ADDR_HEARTS = 0x066F; // In partial units? Or full? (Need to verify, likely upper byte or
                                                   // scaled)
    private static final int ADDR_RUPEES = 0x066D;
    private static final int ADDR_KEYS = 0x066E;
    private static final int ADDR_BOMBS = 0x0658;
    private static final int ADDR_MAP_POS = 0x00EB;

    // Arrays for dynamic entities
    // Enemies 1-6
    private static final int[] ADDR_ENEMY_X = { 0x0071, 0x0072, 0x0073, 0x0074, 0x0075, 0x0076 };
    private static final int[] ADDR_ENEMY_Y = { 0x0085, 0x0086, 0x0087, 0x0088, 0x0089, 0x008A };
    private static final int[] ADDR_ENEMY_ID = { 0x0350, 0x0351, 0x0352, 0x0353, 0x0354, 0x0355 }; // Enemy Type

    // Projectiles likely follow similar patterns or are scattered.
    // Based on research:
    // Enemy Proj X: 77, 78, 79, 7A
    // Enemy Proj Y: 8B, 8C, 8D, 8E
    private static final int[] ADDR_PROJ_X = { 0x0077, 0x0078, 0x0079, 0x007A };
    private static final int[] ADDR_PROJ_Y = { 0x008B, 0x008C, 0x008D, 0x008E };

    public ZeldaGameState(HeadlessNES nes) {
        this.nes = nes;
    }

    public int getLinkX() {
        return nes.readCpuRam(ADDR_LINK_X);
    }

    public int getLinkY() {
        return nes.readCpuRam(ADDR_LINK_Y);
    }

    public int getLinkDir() {
        return nes.readCpuRam(ADDR_LINK_DIR);
    }

    public int getHearts() {
        // According to RAM map, 0x66F is heart containers/health.
        // It might be represented as 1 byte = 8 bits, or larger.
        // Usually, 1 heart = 0x10 or so?
        // For now return raw value.
        return nes.readCpuRam(ADDR_HEARTS);
    }

    public boolean isEnemyActive(int slot) {
        // Usually 0 or 0xFF indicates inactive.
        // Checking the Y coordinate or Type ID
        // Often Y coordinate > 0xF0 means off-screen/dead
        int y = nes.readCpuRam(ADDR_ENEMY_Y[slot]);
        return y < 0xF0 && y > 0;
    }

    public int getEnemyX(int slot) {
        return nes.readCpuRam(ADDR_ENEMY_X[slot]);
    }

    public int getEnemyY(int slot) {
        return nes.readCpuRam(ADDR_ENEMY_Y[slot]);
    }

    public int getEnemyType(int slot) {
        return nes.readCpuRam(ADDR_ENEMY_ID[slot]);
    }

    // Projectiles
    public boolean isProjectileActive(int slot) {
        int y = nes.readCpuRam(ADDR_PROJ_Y[slot]);
        return y < 0xF0 && y > 0;
    }

    public int getProjectileX(int slot) {
        return nes.readCpuRam(ADDR_PROJ_X[slot]);
    }

    public int getProjectileY(int slot) {
        return nes.readCpuRam(ADDR_PROJ_Y[slot]);
    }

    public int getGameMode() {
        return nes.readCpuRam(ADDR_LINK_STATE);
    }

    public int getRupees() {
        return nes.readCpuRam(ADDR_RUPEES);
    }

    public int getKeys() {
        return nes.readCpuRam(ADDR_KEYS);
    }

    public int getBombs() {
        return nes.readCpuRam(ADDR_BOMBS);
    }

    public int getMapPosition() {
        return nes.readCpuRam(ADDR_MAP_POS);
    }
}
