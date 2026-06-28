package com.mochanes.emulator;

public class Controller {

    private int buttons = 0; // Cleaned state for NES
    private int rawButtons = 0; // Physical state of keyboard
    private int shiftRegister = 0;
    private boolean strobe = false;

    // Button Mappings (Index in bitfield)
    // 0: A
    // 1: B
    // 2: Select
    // 3: Start
    // 4: Up
    // 5: Down
    // 6: Left
    // 7: Right

    public void setButtonPressed(int buttonBit, boolean pressed) {
        if (pressed) {
            rawButtons |= (1 << buttonBit);
        } else {
            rawButtons &= ~(1 << buttonBit);
        }

        // SOCD Cleaning (Simultaneous Opposing Cardinal Directions)
        // Standard Nintendo behavior (or safer): Left+Right = None, Up+Down = None
        buttons = rawButtons;

        boolean left = (buttons & (1 << 6)) != 0;
        boolean right = (buttons & (1 << 7)) != 0;
        boolean up = (buttons & (1 << 4)) != 0;
        boolean down = (buttons & (1 << 5)) != 0;

        if (left && right) {
            buttons &= ~(1 << 6);
            buttons &= ~(1 << 7);
        }

        if (up && down) {
            buttons &= ~(1 << 4);
            buttons &= ~(1 << 5);
        }
    }

    public void write(int data) {
        // If bit 0 is 1, strobe is ON
        strobe = (data & 0x01) == 1;
        if (strobe) {
            shiftRegister = buttons;
        }
    }

    public int read() {
        int val = 0;
        if (strobe) {
            // While strobe is high, it keeps reloading the buttons
            shiftRegister = buttons; // Reload constantly
            val = shiftRegister & 1;
        } else {
            // Return LSB
            val = shiftRegister & 1;
            // Shift
            shiftRegister >>= 1;
            // High bits usually set to 1
            shiftRegister |= 0x80;
        }
        return val;
    }

    public Controller copy() {
        Controller c = new Controller();
        c.buttons = this.buttons;
        c.rawButtons = this.rawButtons;
        c.shiftRegister = this.shiftRegister;
        c.strobe = this.strobe;
        return c;
    }

    public void loadState(Controller source) {
        this.buttons = source.buttons;
        this.rawButtons = source.rawButtons;
        this.shiftRegister = source.shiftRegister;
        this.strobe = source.strobe;
    }
}
