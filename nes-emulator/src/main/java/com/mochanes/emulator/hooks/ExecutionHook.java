package com.mochanes.emulator.hooks;

public interface ExecutionHook {
    // Called before executing an instruction at PC
    void onExecute(int pc, int opcode, int opcodeByte2, int opcodeByte3);
}
