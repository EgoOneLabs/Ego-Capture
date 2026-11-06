package com.egouvc.phone.export;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class NominalImuProfileRegression {
    private static void near(double actual, double expected) {
        if (Math.abs(actual-expected)>1e-11) throw new AssertionError(actual+" != "+expected);
    }
    public static void check(long time, int ax,int ay,int az,int gx,int gy,int gz) {
        String[] row=EgoRecordFormat.imuLine(time,ax,ay,az,gx,gy,gz,NominalImuProfile.scale()).trim().split(",");
        if (row.length!=11 || !row[0].equals(EgoRecordFormat.seconds(time))) throw new AssertionError("schema/time");
        for (int i=1;i<5;i++) if (!row[i].equals("0.0")) throw new AssertionError("orientation changed");
        int[] g={gx,gy,gz},a={ax,ay,az};
        for (int i=0;i<3;i++) {
            near(Double.parseDouble(row[5+i]),g[i]/16.4*Math.PI/180.0);
            near(Double.parseDouble(row[8+i]),a[i]/4096.0*9.80665);
        }
    }
    public static void knownSamples() {
        check(1793351091166671038L,4096,0,-4096,164,0,-164);
        check(1L,0,4096,0,0,164,0);
        check(1L,-32768,32767,0,-32768,32767,0);
        check(1L,0,0,0,0,0,0);
        String[] one=EgoRecordFormat.imuLine(1,0,0,4096,0,0,164,NominalImuProfile.scale()).trim().split(",");
        near(Double.parseDouble(one[10]),9.80665);
        near(Double.parseDouble(one[7]),Math.PI/18);
    }
    public static void main(String[] args) throws Exception {
        Locale old=Locale.getDefault();
        try {Locale.setDefault(Locale.GERMANY);knownSamples();} finally {Locale.setDefault(old);}
        long n=0;
        if (args.length>0) try (BufferedReader in=Files.newBufferedReader(Path.of(args[0]))) {
            if (!"timestamp,ax,ay,az,gx,gy,gz".equals(in.readLine())) throw new AssertionError("fixture header");
            String line;
            while ((line=in.readLine())!=null) {
                String[] r=line.split(",");String[] stamp=r[0].split("\\.");
                long t=Math.addExact(Math.multiplyExact(Long.parseLong(stamp[0]),1_000_000_000L),Long.parseLong(stamp[1]));
                check(t,Integer.parseInt(r[1]),Integer.parseInt(r[2]),Integer.parseInt(r[3]),
                        Integer.parseInt(r[4]),Integer.parseInt(r[5]),Integer.parseInt(r[6]));n++;
            }
        }
        System.out.println("PASS nominal SI formatter, known values / signs / extrema / locale / original time; corpus rows="+n);
    }
}
