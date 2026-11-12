package com.egouvc.phone.export;
import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;
public class AvcMatroskaWriterTest {
    @Test public void annexBAndAvccCarryTheSameNalPayloads() throws Exception {
        byte[] annex={0,0,0,1,0x65,0x12,0x34,0,0,1,0x41,0x56};
        byte[] avcc={0,0,0,3,0x65,0x12,0x34,0,0,0,2,0x41,0x56};
        assertArrayEquals(avcc,AvcMatroskaWriter.lengthPrefixed(annex));
        assertArrayEquals(avcc,AvcMatroskaWriter.lengthPrefixed(avcc));
        byte[] ambiguous = new byte[260];Arrays.fill(ambiguous,(byte)0x77);
        ambiguous[0]=0;ambiguous[1]=0;ambiguous[2]=1;ambiguous[3]=0;ambiguous[4]=0x65;
        assertArrayEquals(ambiguous,AvcMatroskaWriter.lengthPrefixed(ambiguous));
        assertThrows(IOException.class,()->AvcMatroskaWriter.lengthPrefixed(new byte[]{0,0,0,9,0x65}));
    }
    @Test public void rejectsMissingConfigNonKeyStartAndBackwardsPts() throws Exception {
        File file=File.createTempFile("mkv-contract",".mkv");
        byte[] csd={0,0,0,1,0x67,0x42,0,0x1f,0x55,0,0,0,1,0x68,0x66};
        assertThrows(IOException.class,()->AvcMatroskaWriter.avcConfig(new byte[]{1},null));
        try(AvcMatroskaWriter writer=new AvcMatroskaWriter(file,128,64,csd,null)) {
            byte[] frame={0,0,0,1,0x65,0x12};
            assertThrows(IOException.class,()->writer.write(frame,0,false));
            writer.write(frame,0,true);
            assertThrows(IOException.class,()->writer.write(frame,0,true));
            writer.write(frame,33333,false);assertEquals(2,writer.frames());
        } finally {file.delete();}
    }
}
