package com.egouvc.phone.export;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** One independently decodable JPEG per block. No video re-encoding. */
public final class MjpegMatroskaWriter implements Closeable {
    private final OutputStream out;
    private long lastPtsUs = -1;
    private long frames;
    public MjpegMatroskaWriter(File file, int width, int height) throws IOException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid dimensions");
        out = new BufferedOutputStream(new FileOutputStream(file), 1 << 20);
        try {
            out.write(element(0x1A45DFA3L, join(uint(0x4286,1),uint(0x42F7,1),uint(0x42F2,4),uint(0x42F3,8),
                text(0x4282,"matroska"),uint(0x4287,4),uint(0x4285,2))));
            out.write(id(0x18538067L));
            out.write(new byte[]{1,-1,-1,-1,-1,-1,-1,-1});
            out.write(element(0x1549A966L,join(uint(0x2AD7B1,1_000_000),text(0x4D80,"EgoStereoIMU"),text(0x5741,"EgoStereoIMU"))));
            byte[] video=element(0xE0,join(uint(0xB0,width),uint(0xBA,height)));
            out.write(element(0x1654AE6BL,element(0xAE,join(uint(0xD7,1),uint(0x73C5,1),uint(0x83,1),
                uint(0x9C,0),text(0x86,"V_MJPEG"),video))));
        } catch (IOException e) {out.close();throw e;}
    }
    public void write(byte[] jpeg, long ptsUs) throws IOException {
        if (ptsUs < 0 || ptsUs <= lastPtsUs) throw new IOException("Non-increasing frame timestamp");
        if (jpeg == null || jpeg.length < 4 || (jpeg[0]&255)!=255 || (jpeg[1]&255)!=216 ||
            (jpeg[jpeg.length-2]&255)!=255 || (jpeg[jpeg.length-1]&255)!=217)
            throw new IOException("Invalid cropped JPEG boundaries");
        ByteArrayOutputStream block = new ByteArrayOutputStream(jpeg.length+4);
        block.write(0x81);block.write(0);block.write(0);block.write(0x80);block.write(jpeg);
        out.write(element(0x1F43B675L,join(uint(0xE7,ptsUs/1000),element(0xA3,block.toByteArray()))));
        lastPtsUs=ptsUs;frames++;
    }
    public long frames() {return frames;}
    public void close() throws IOException {out.close();}
    private static byte[] uint(long tag,long value) throws IOException {
        if(value<0)throw new IOException("Negative EBML integer");
        int n=1;while(n<8&&(value>>>(n*8))!=0)n++;
        byte[] b=new byte[n];for(int i=n-1;i>=0;i--){b[i]=(byte)value;value>>>=8;}return element(tag,b);
    }
    private static byte[] text(long tag,String s) throws IOException {return element(tag,s.getBytes(StandardCharsets.UTF_8));}
    private static byte[] id(long value) {
        int n=1;while(n<8&&(value>>>(n*8))!=0)n++;
        byte[] b=new byte[n];for(int i=n-1;i>=0;i--){b[i]=(byte)value;value>>>=8;}return b;
    }
    private static byte[] element(long tag,byte[] body) throws IOException {
        int n=1;while(n<8&&body.length>=(1L<<(7*n))-1)n++;
        long value=body.length|(1L<<(7*n));byte[] size=new byte[n];
        for(int i=n-1;i>=0;i--){size[i]=(byte)value;value>>>=8;}
        return join(id(tag),size,body);
    }
    private static byte[] join(byte[]...arrays) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] a:arrays)b.write(a);return b.toByteArray();
    }
}
