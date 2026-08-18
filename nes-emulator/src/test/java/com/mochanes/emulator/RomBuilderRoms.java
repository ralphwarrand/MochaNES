package com.mochanes.emulator;

/** Small assembled ROMs shared between tests. */
final class RomBuilderRoms {

    private RomBuilderRoms() {
    }

    /** Fills CHR, a nametable and the palette, then scrolls a pixel per frame. */
    static byte[] scrollingBackground() {
        RomBuilder a = new RomBuilder();
        a.sei().cld().ldxImm(0xFF).txs();
        a.label("warm1").bitAbs(0x2002).bpl("warm1");
        a.label("warm2").bitAbs(0x2002).bpl("warm2");

        a.ldaImm(0x3F).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00);
        a.label("pal").txa().adcImm(0x01).staAbs(0x2007).inx().cpxImm(0x20).bne("pal");

        a.ldaImm(0x00).staAbs(0x2006).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("chr").tya().adcImm(0x07).staAbs(0x2007).iny().bne("chr");
        a.inx().cpxImm(0x20).bne("chr");

        a.ldaImm(0x20).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("nt").tya().staAbs(0x2007).iny().bne("nt");
        a.inx().cpxImm(0x04).bne("nt");

        a.ldaImm(0x00).staZp(0x10);
        a.ldaImm(0x00).staAbs(0x2000);
        a.ldaImm(0x0A).staAbs(0x2001);

        a.label("loop");
        a.label("vbl").bitAbs(0x2002).bpl("vbl");
        a.incZp(0x10).ldaZp(0x10).staAbs(0x2005).ldaImm(0x00).staAbs(0x2005);
        a.jmp("loop");

        return a.build(false);
    }
}
