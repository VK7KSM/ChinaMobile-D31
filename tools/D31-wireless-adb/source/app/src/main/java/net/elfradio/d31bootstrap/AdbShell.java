package net.elfradio.d31bootstrap;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** 真正的ADB传输，协商旧版PTY或shell_v2。 */
final class AdbShell implements Closeable {
    static final int CNXN=0x4e584e43, AUTH=0x48545541, OPEN=0x4e45504f;
    static final int OKAY=0x59414b4f, WRTE=0x45545257, CLSE=0x45534c43;
    interface Listener { void output(int channel, byte[] data); void closed(Integer exit, String error); }
    static final class Packet {
        final int command, arg0, arg1; final byte[] data;
        Packet(int c,int a,int b,byte[] d){command=c;arg0=a;arg1=b;data=d;}
    }
    private final Socket socket;
    private final Listener listener;
    private final Semaphore credit=new Semaphore(1);
    private final Object outputLock=new Object();
    private volatile boolean closed;
    private int remote, maxPayload;
    private boolean shellV2;
    private final ByteArrayOutputStream shellBuffer=new ByteArrayOutputStream();
    private Integer exit;

    AdbShell(Socket socket,Listener listener)throws IOException {
        this.socket=socket;this.listener=listener;
        try {
            socket.setSoTimeout(10000);
            send(CNXN,0x01000000,4096,"host::features=shell_v2;\0".getBytes(StandardCharsets.UTF_8));
            Packet connect=read(socket.getInputStream());
            if(connect.command==AUTH)throw new IOException("本机ADB要求授权，当前会话未连接");
            if(connect.command!=CNXN||connect.arg1<1024)
                throw new IOException("本机ADB未提供交互终端协议");
            shellV2=hasFeature(new String(connect.data,StandardCharsets.UTF_8),"shell_v2");
            maxPayload=Math.min(4096,connect.arg1);
            send(OPEN,1,0,(shellV2?"shell,v2,pty,TERM=xterm:\0":"shell:\0").getBytes(StandardCharsets.UTF_8));
            Packet opened=read(socket.getInputStream());
            if(opened.command!=OKAY||opened.arg1!=1||opened.arg0==0)throw new IOException("本机ADB拒绝打开终端");
            remote=opened.arg0;socket.setSoTimeout(0);
        }catch(IOException error){socket.close();throw error;}
    }
    static boolean hasFeature(String banner,String feature) {
        for(String part:banner.replace("\0", "").split(";")) {
            int at=part.indexOf("features=");
            if(at>=0)for(String value:part.substring(at+9).split(","))if(value.equals(feature))return true;
        }
        return false;
    }
    boolean shellV2(){return shellV2;}
    void start(){Thread reader=new Thread(this::receive,"elfremote-adb-shell");reader.setDaemon(true);reader.start();}
    void input(byte[] bytes)throws Exception { frame(0,bytes); }
    void resize(int rows,int columns)throws Exception {
        if(rows<1||rows>500||columns<1||columns>500)throw new IllegalArgumentException("终端尺寸无效");
        // 旧协议无窗口控制通道；禁止把控制帧或stty命令混入用户输入。
        if(shellV2)frame(5,(rows+"x"+columns+",0x0\0").getBytes(StandardCharsets.UTF_8));
    }
    private synchronized void frame(int channel,byte[] bytes)throws Exception {
        if(bytes.length>65536)throw new IOException("单次终端输入过大");
        ByteArrayOutputStream frame=new ByteArrayOutputStream();
        if(shellV2){frame.write(channel);put(frame,bytes.length);}frame.write(bytes);
        byte[] payload=frame.toByteArray();
        for(int at=0;at<payload.length;at+=maxPayload){
            if(closed||!credit.tryAcquire(5,TimeUnit.SECONDS))throw new IOException("ADB输入未获确认");
            if(closed)throw new IOException("ADB会话已关闭");
            send(WRTE,1,remote,java.util.Arrays.copyOfRange(payload,at,Math.min(payload.length,at+maxPayload)));
        }
    }
    private void receive(){String error="";
        try {
            while(!closed){
                Packet packet=read(socket.getInputStream());
                if(packet.arg0!=remote||packet.arg1!=1)throw new IOException("ADB会话标识不匹配");
                if(packet.command==OKAY){if(credit.availablePermits()==0)credit.release();}
                else if(packet.command==WRTE){
                    send(OKAY,1,remote,new byte[0]);
                    if(shellV2){
                        if(shellBuffer.size()+packet.data.length>1048581)throw new IOException("ADB输出帧过大");
                        shellBuffer.write(packet.data);consume();
                    }else listener.output(1,packet.data);
                }else if(packet.command==CLSE){send(CLSE,1,remote,new byte[0]);break;}
                else throw new IOException("ADB返回未知消息");
            }
        }catch(Exception failure){if(!closed)error=failure.getMessage()==null?"ADB连接中断":failure.getMessage();}
        finally{close();listener.closed(exit,error);}
    }
    private void consume()throws IOException {
        byte[] all=shellBuffer.toByteArray();int at=0;
        while(all.length-at>=5){
            int channel=all[at]&255,n=integer(all,at+1);
            if(n<0||n>1048576)throw new IOException("ADB输出长度无效");
            if(all.length-at<5+n)break;
            if(channel==1||channel==2)listener.output(channel,java.util.Arrays.copyOfRange(all,at+5,at+5+n));
            else if(channel==3){if(n!=1)throw new IOException("ADB退出状态无效");exit=all[at+5]&255;}
            else throw new IOException("ADB输出通道无效");
            at+=5+n;
        }
        shellBuffer.reset();shellBuffer.write(all,at,all.length-at);
    }
    private void send(int c,int a,int b,byte[] d)throws IOException {
        synchronized(outputLock){write(socket.getOutputStream(),new Packet(c,a,b,d));}
    }
    public void close(){closed=true;credit.release();try{socket.close();}catch(IOException ignored){}}
    static int integer(byte[] b,int at){return (b[at]&255)|((b[at+1]&255)<<8)|((b[at+2]&255)<<16)|((b[at+3]&255)<<24);}
    static void put(OutputStream out,int v)throws IOException{for(int n=0;n<4;n++)out.write(v>>>(8*n));}
    static int checksum(byte[] b){int s=0;for(byte v:b)s+=v&255;return s;}
    static void write(OutputStream out,Packet p)throws IOException {
        put(out,p.command);put(out,p.arg0);put(out,p.arg1);put(out,p.data.length);put(out,checksum(p.data));put(out,p.command^0xffffffff);out.write(p.data);out.flush();
    }
    static Packet read(InputStream in)throws IOException {
        byte[] h=exact(in,24);int c=integer(h,0),size=integer(h,12);
        if(integer(h,20)!=(c^0xffffffff)||size<0||size>1048576)throw new IOException("ADB消息头无效");
        byte[] payload=exact(in,size);
        if(integer(h,16)!=checksum(payload))throw new IOException("ADB消息校验失败");
        return new Packet(c,integer(h,4),integer(h,8),payload);
    }
    private static byte[] exact(InputStream in,int size)throws IOException {
        byte[] b=new byte[size];int at=0,n;while(at<size){n=in.read(b,at,size-at);if(n<0)throw new EOFException("ADB连接已结束");if(n==0)continue;at+=n;}return b;
    }
}
