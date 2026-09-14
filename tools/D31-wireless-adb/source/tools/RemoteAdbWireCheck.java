package net.elfradio.d31bootstrap;

import java.net.*;
import java.nio.charset.StandardCharsets;

public final class RemoteAdbWireCheck {
    public static void main(String[] args) {
        try{run(args);}catch(Exception failure){
            System.out.println("ADB_DIAGNOSTIC_FAILED type="+failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
    private static void run(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("需要已确认的目标地址");
        int port=AdbControl.currentPort();
        try(Socket socket=new Socket()){
            socket.connect(new InetSocketAddress(args[0],port),3000);socket.setSoTimeout(5000);
            System.out.println("TCP_CONNECTED port="+port);
            AdbShell.write(socket.getOutputStream(),new AdbShell.Packet(AdbShell.CNXN,0x01000000,4096,"host::features=shell_v2;\0".getBytes(StandardCharsets.UTF_8)));
            System.out.println("CNXN_SENT");
            AdbShell.Packet packet=AdbShell.read(socket.getInputStream());
            System.out.println("REPLY command="+Integer.toHexString(packet.command)+" length="+packet.data.length+" arg0="+packet.arg0+" arg1="+packet.arg1);
            if(packet.command==AdbShell.CNXN){
                System.out.println("CNXN_BANNER="+new String(packet.data,StandardCharsets.UTF_8).replace('\0',' '));
                AdbShell.write(socket.getOutputStream(),new AdbShell.Packet(AdbShell.OPEN,1,0,"shell:\0".getBytes(StandardCharsets.UTF_8)));
                System.out.println("OPEN_SENT");
                AdbShell.Packet opened=AdbShell.read(socket.getInputStream());
                System.out.println("OPEN_REPLY="+Integer.toHexString(opened.command));
            }
        }
    }
}
