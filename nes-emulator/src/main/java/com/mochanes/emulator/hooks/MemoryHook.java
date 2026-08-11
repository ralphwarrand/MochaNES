package com.mochanes.emulator.hooks;

public interface MemoryHook {
    // Called BEFORE read/write? Or AFTER?
    // Usually listeners want to know what happened.
    // If we want to intercept, we need return values. For now, just observer.
    void onRead(int address, int value);

    void onWrite(int address, int value);
}
