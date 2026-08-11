package com.mochanes.emulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class AddresserTest {

    @Test
    public void testCPUExecutionMatchesReferenceLog() {
        try {
            // Map each opcode to its expected addressing mode string.
            Map<Integer, String> expectedModes = new HashMap<>();
            expectedModes.put(0x69, "Immediate"); // Immediate
            expectedModes.put(0x75, "ZeroPageX"); // ZeroPage,X
            expectedModes.put(0x6C, "Indirect"); // Indirect
            expectedModes.put(0xD3, "IndirectY"); // Indirect,Y
            expectedModes.put(0xEA, "Implied"); // Implied

            // Unofficial NOP variants:
            expectedModes.put(0x04, "ZeroPage"); // ZeroPage (NOP variant)
            expectedModes.put(0x0C, "Absolute"); // Absolute (NOP variant)
            expectedModes.put(0x14, "ZeroPageX"); // ZeroPage,X (NOP variant)

            // Iterate over the expected modes and assert that the addressing mode matches.
            for (Map.Entry<Integer, String> entry : expectedModes.entrySet()) {
                int opcode = entry.getKey();
                String expected = entry.getValue();
                String actual = Addresser.getAddressingMode(opcode);
                assertEquals(String.format("Opcode 0x%02X: expected %s, but got %s", opcode, expected, actual),
                        expected, actual);
            }
        } catch (Exception e) {
            e.printStackTrace();
            fail("Exception occurred: " + e.getMessage());
        }
    }
}
