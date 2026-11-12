package com.egouvc.phone.export;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Single AVC track, no B frames. Streaming clusters, millisecond timecodes, no seeking index.
 * Caller supplies AVC access units in presentation order. Exact host timestamps live in sidecars.
 */
public final class AvcMatroskaWriter implements Closeable {
    private final OutputStream out;
    private long lastPtsUs = -1;
    private long frames;
    public AvcMatroskaWriter(File file, int width, int height, byte[] csd0, byte[] csd1) throws IOException {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid dimensions");
        byte[] config = avcConfig(csd0, csd1); // Validate before opening destination.
        out = new BufferedOutputStream(new FileOutputStream(file), 1 << 20);
        try {
            out.write(element(0x1A45DFA3L, join(uint(0x4286,1),uint(0x42F7,1),uint(0x42F2,4),uint(0x42F3,8),
                text(0x4282,"matroska"),uint(0x4287,4),uint(0x4285,2))));
            out.write(id(0x18538067L));
            out.write(new byte[]{1,-1,-1,-1,-1,-1,-1,-1}); // Unknown-size Segment.
            out.write(element(0x1549A966L,join(uint(0x2AD7B1,1_000_000),text(0x4D80,"EgoStereoIMU"),text(0x5741,"EgoStereoIMU"))));
            byte[] video=element(0xE0,join(uint(0xB0,width),uint(0xBA,height)));
            out.write(element(0x1654AE6BL,element(0xAE,join(uint(0xD7,1),uint(0x73C5,1),uint(0x83,1),
                uint(0x9C,0),text(0x86,"V_MPEG4/ISO/AVC"),element(0x63A2,config),video))));
        } catch (IOException e) {out.close();throw e;}
    }
    public void write(byte[] accessUnit, long ptsUs, boolean key) throws IOException {
        if (ptsUs < 0 || ptsUs <= lastPtsUs) throw new IOException("Non-increasing encoded timestamp");
        if (frames == 0 && !key) throw new IOException("First AVC frame is not a key frame");
        byte[] nal = lengthPrefixed(accessUnit);
        ByteArrayOutputStream block = new ByteArrayOutputStream(nal.length+4);
        block.write(0x81); block.write(0);block.write(0);block.write(key?0x80:0);block.write(nal);
        out.write(element(0x1F43B675L,join(uint(0xE7,ptsUs/1000),element(0xA3,block.toByteArray()))));
        lastPtsUs=ptsUs;frames++;
    }
    public long frames() {return frames;}
    public void close() throws IOException {out.close();}

    public static byte[] avcConfig(byte[] a, byte[] b) throws IOException {
        List<byte[]> all=new ArrayList<>(nals(a));if(b!=null&&b.length>0)all.addAll(nals(b));
        byte[] sps=null,pps=null;
        for(byte[] n:all) {if((n[0]&31)==7)sps=n;if((n[0]&31)==8)pps=n;}
        if(sps==null||sps.length<4||pps==null||sps.length>65535||pps.length>65535)
            throw new IOException("Missing or invalid AVC SPS/PPS");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream w=new DataOutputStream(bytes);
        w.write(new byte[]{1,sps[1],sps[2],sps[3],(byte)255,(byte)225});
        w.writeShort(sps.length);w.write(sps);w.writeByte(1);w.writeShort(pps.length);w.write(pps);
        return bytes.toByteArray();
    }
    public static byte[] lengthPrefixed(byte[] data) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream w=new DataOutputStream(bytes);
        for(byte[] n:nals(data)) {w.writeInt(n.length);w.write(n);}
        return bytes.toByteArray();
    }
    public static List<byte[]> nals(byte[] data) throws IOException {
        if(data==null||data.length==0)throw new IOException("Empty AVC data");
        // Validate AVCC framing first: a 256-byte length starts 00 00 01 00,
        // which must not be mistaken for an Annex B start code.
        List<byte[]> framed=new ArrayList<>();int cursor=0;
        while(cursor+4<=data.length) {
            int n=ByteBuffer.wrap(data,cursor,4).getInt();cursor+=4;
            if(n<=0||n>data.length-cursor){cursor=-1;break;}
            framed.add(Arrays.copyOfRange(data,cursor,cursor+n));cursor+=n;
        }
        if(cursor==data.length&&!framed.isEmpty())return framed;
        List<byte[]> result=new ArrayList<>();int first=findStart(data,0);
        if(first==0) {
            int at=0;
            while(at<data.length) {
                int size=startSize(data,at);if(size==0)throw new IOException("Invalid Annex B start");
                int begin=at+size,next=findStart(data,begin),end=next<0?data.length:next;
                while(end>begin&&data[end-1]==0)end--; // Annex B trailing_zero_8bits.
                if(end>begin)result.add(Arrays.copyOfRange(data,begin,end));
                if(next<0)break;at=next;
            }
        } else {
            int at=0;
            while(at<data.length) {
                if(at+4>data.length)throw new IOException("Truncated AVC NAL length");
                int n=ByteBuffer.wrap(data,at,4).getInt();at+=4;
                if(n<=0||n>data.length-at)throw new IOException("Invalid AVC NAL length");
                result.add(Arrays.copyOfRange(data,at,at+n));at+=n;
            }
        }
        if(result.isEmpty())throw new IOException("No AVC NALs");return result;
    }
    private static int startSize(byte[] a,int i) {
        if(i+2<a.length&&a[i]==0&&a[i+1]==0) {
            if(a[i+2]==1)return 3;
            if(i+3<a.length&&a[i+2]==0&&a[i+3]==1)return 4;
        }return 0;
    }
    private static int findStart(byte[] a,int i) {for(;i<a.length-2;i++)if(startSize(a,i)>0)return i;return -1;}
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
