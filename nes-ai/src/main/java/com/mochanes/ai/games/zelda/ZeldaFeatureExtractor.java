package com.mochanes.ai.games.zelda;

/**
 * Converts raw ZeldaGameState into a float array (feature vector) for the
 * Neural Network.
 */
public class ZeldaFeatureExtractor {

    // Input Size Calculation:
    // Link: X, Y, Dir(4), Health = 7
    // Meta: Rupees, Keys, Bombs, MapX, MapY = 5
    // Enemies (6): Active, dX, dY, Dist, Type = 5 * 6 = 30
    // Projectiles (4): Active, dX, dY, Dist = 4 * 4 = 16
    // Totals = 58 floats
    public static final int INPUT_SIZE = 58;

    public static float[] extract(ZeldaGameState state) {
        float[] features = new float[INPUT_SIZE];
        int ptr = 0;

        // --- Link State ---
        features[ptr++] = state.getLinkX() / 256.0f;
        features[ptr++] = state.getLinkY() / 240.0f;

        // One-Hot Direction
        int dir = state.getLinkDir(); // 1=Right, 2=Left, 4=Down, 8=Up (Verify map)
        // Usually NES directions are bitmasks, but Zelda might stick to 0,1,2,3 or
        // 1,2,4,8.
        // Research suggests: Link's Dir at 0x98 might be 1=Right, 2=Left, 4=Down, 8=Up.
        features[ptr++] = (dir == 1) ? 1.0f : 0.0f;
        features[ptr++] = (dir == 2) ? 1.0f : 0.0f;
        features[ptr++] = (dir == 4) ? 1.0f : 0.0f;
        features[ptr++] = (dir == 8) ? 1.0f : 0.0f;

        // Stats
        features[ptr++] = state.getHearts() / 16.0f; // Approx normalization
        
        // Inventory
        features[ptr++] = state.getRupees() / 255.0f;
        features[ptr++] = state.getKeys() / 255.0f;
        features[ptr++] = state.getBombs() / 255.0f;
        
        // Map Position (0xEB -> upper nibble Y, lower nibble X)
        int mapPos = state.getMapPosition();
        int mapX = mapPos & 0x0F;
        int mapY = (mapPos >> 4) & 0x0F;
        features[ptr++] = mapX / 15.0f; // Max 16 width
        features[ptr++] = mapY / 7.0f;  // Max 8 height

        // --- Enemies (6 slots) ---
        for (int i = 0; i < 6; i++) {
            if (state.isEnemyActive(i)) {
                float ex = state.getEnemyX(i) / 256.0f;
                float ey = state.getEnemyY(i) / 240.0f;
                float lx = features[0];
                float ly = features[1];

                float dx = ex - lx;
                float dy = ey - ly;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                features[ptr++] = 1.0f; // Active
                features[ptr++] = dx;
                features[ptr++] = dy;
                features[ptr++] = dist;
                features[ptr++] = state.getEnemyType(i) / 255.0f; // Type ID normalized
            } else {
                features[ptr++] = 0.0f; // Inactive
                features[ptr++] = 0.0f;
                features[ptr++] = 0.0f;
                features[ptr++] = 1.0f; // Max dist
                features[ptr++] = 0.0f;
            }
        }

        // --- Projectiles (4 slots) ---
        for (int i = 0; i < 4; i++) {
            if (state.isProjectileActive(i)) {
                float px = state.getProjectileX(i) / 256.0f;
                float py = state.getProjectileY(i) / 240.0f;
                float lx = features[0];
                float ly = features[1];

                float dx = px - lx;
                float dy = py - ly;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);

                features[ptr++] = 1.0f;
                features[ptr++] = dx;
                features[ptr++] = dy;
                features[ptr++] = dist;
            } else {
                features[ptr++] = 0.0f;
                features[ptr++] = 0.0f;
                features[ptr++] = 0.0f;
                features[ptr++] = 1.0f;
            }
        }

        return features;
    }
}
