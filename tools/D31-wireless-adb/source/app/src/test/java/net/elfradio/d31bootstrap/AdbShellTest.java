package net.elfradio.d31bootstrap;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class AdbShellTest {
    @Test public void legacyPtyPreservesInputAndUnknownExit()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Future<?> remote=executor.submit(()->{try(Socket s=server.accept()){
                s.setSoTimeout(5000);
                assertEquals(AdbShell.CNXN,AdbShell.read(s.getInputStream()).command);
                packet(s,AdbShell.CNXN,0x01000000,4096,bytes("device::ro.product.name=d31;\0"));
                assertArrayEquals(bytes("shell:\0"),AdbShell.read(s.getInputStream()).data);
                packet(s,AdbShell.OKAY,42,1,new byte[0]);
                packet(s,AdbShell.WRTE,42,1,bytes("legacy ready\n"));
                assertEquals(AdbShell.OKAY,AdbShell.read(s.getInputStream()).command);
                assertArrayEquals(bytes("pwd\n"),AdbShell.read(s.getInputStream()).data);
                packet(s,AdbShell.OKAY,42,1,new byte[0]);
                assertArrayEquals(new byte[]{3},AdbShell.read(s.getInputStream()).data);
                packet(s,AdbShell.OKAY,42,1,new byte[0]);
                packet(s,AdbShell.CLSE,42,1,new byte[0]);
                assertEquals(AdbShell.CLSE,AdbShell.read(s.getInputStream()).command);
            }catch(Exception e){throw new RuntimeException(e);}});
            ByteArrayOutputStream output=new ByteArrayOutputStream();CountDownLatch prompt=new CountDownLatch(1),done=new CountDownLatch(1);
            Integer[] exit={123};String[] error={null};
            try(AdbShell client=new AdbShell(new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()),new AdbShell.Listener(){
                public void output(int c,byte[] b){output.write(b,0,b.length);prompt.countDown();}
                public void closed(Integer code,String reason){exit[0]=code;error[0]=reason;done.countDown();}
            })){
                assertFalse(client.shellV2());client.start();assertTrue(prompt.await(5,TimeUnit.SECONDS));
                client.resize(24,80);client.input(bytes("pwd\n"));client.input(new byte[]{3});
                assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(exit[0]);assertEquals("",error[0]);
                assertEquals("legacy ready\n",output.toString("UTF-8"));
            }
            remote.get(5,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
    }
    @Test public void featuresAreExactTokens(){
        assertTrue(AdbShell.hasFeature("device::features=cmd,shell_v2;\0","shell_v2"));
        assertFalse(AdbShell.hasFeature("device::features=no_shell_v2;\0","shell_v2"));
        assertFalse(AdbShell.hasFeature("device::ro.product.name=shell_v2;\0","shell_v2"));
    }
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static void packet(Socket s,int c,int a,int b,byte[] data)throws Exception{
        AdbShell.write(s.getOutputStream(),new AdbShell.Packet(c,a,b,data));
    }
    private static byte[] shell(int channel,byte[] data)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(channel);AdbShell.put(out,data.length);out.write(data);return out.toByteArray();
    }
    private static void handshake(Socket s)throws Exception{
        s.setSoTimeout(5000);
        assertEquals(AdbShell.CNXN,AdbShell.read(s.getInputStream()).command);
        packet(s,AdbShell.CNXN,0x01000000,4096,bytes("device::features=shell_v2;\0"));
        AdbShell.Packet open=AdbShell.read(s.getInputStream());
        assertEquals(AdbShell.OPEN,open.command);assertEquals(1,open.arg0);
        assertEquals("shell,v2,pty,TERM=xterm:\0",new String(open.data,StandardCharsets.UTF_8));
        packet(s,AdbShell.OKAY,42,1,new byte[0]);
    }
    @Test public void realWireHandshakeFragmentedOutputInputAndExit()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Future<?> remote=executor.submit(()->{try(Socket s=server.accept()){
                handshake(s);
                byte[] frame=shell(1,bytes("终端 ready\n"));
                for(byte[] part:new byte[][]{java.util.Arrays.copyOfRange(frame,0,3),java.util.Arrays.copyOfRange(frame,3,frame.length)}){
                    packet(s,AdbShell.WRTE,42,1,part);assertEquals(AdbShell.OKAY,AdbShell.read(s.getInputStream()).command);
                }
                AdbShell.Packet input=AdbShell.read(s.getInputStream());
                assertEquals(AdbShell.WRTE,input.command);assertArrayEquals(shell(0,bytes("pwd\n")),input.data);
                packet(s,AdbShell.OKAY,42,1,new byte[0]);
                AdbShell.Packet interrupt=AdbShell.read(s.getInputStream());assertArrayEquals(shell(0,new byte[]{3}),interrupt.data);
                packet(s,AdbShell.OKAY,42,1,new byte[0]);
                packet(s,AdbShell.WRTE,42,1,shell(3,new byte[]{7}));assertEquals(AdbShell.OKAY,AdbShell.read(s.getInputStream()).command);
                packet(s,AdbShell.CLSE,42,1,new byte[0]);assertEquals(AdbShell.CLSE,AdbShell.read(s.getInputStream()).command);
            }catch(Exception e){throw new RuntimeException(e);}});
            ByteArrayOutputStream output=new ByteArrayOutputStream();CountDownLatch finished=new CountDownLatch(1),prompt=new CountDownLatch(1);Integer[] result={null};String[] error={null};
            try(AdbShell client=new AdbShell(new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()),new AdbShell.Listener(){
                public void output(int channel,byte[] data){assertEquals(1,channel);output.write(data,0,data.length);prompt.countDown();}
                public void closed(Integer exit,String reason){result[0]=exit;error[0]=reason;finished.countDown();}
            })){
                client.start();assertTrue(prompt.await(5,TimeUnit.SECONDS));client.input(bytes("pwd\n"));client.input(new byte[]{3});assertTrue(finished.await(5,TimeUnit.SECONDS));
                assertEquals(Integer.valueOf(7),result[0]);assertEquals("",error[0]);assertEquals("终端 ready\n",output.toString("UTF-8"));
            }
            remote.get(5,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
    }
    @Test public void invalidChecksumLengthAndTruncationAreRejected()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();AdbShell.write(out,new AdbShell.Packet(AdbShell.WRTE,1,2,bytes("hello")));
        for(int mode=0;mode<3;mode++){
            byte[] wire=out.toByteArray();if(mode==0)wire[24]^=1;if(mode==1)wire[15]=0x7f;if(mode==2)wire=java.util.Arrays.copyOf(wire,wire.length-1);
            try{AdbShell.read(new ByteArrayInputStream(wire));fail("无效消息不应通过");}catch(IOException expected){}
        }
    }
    @Test public void authorizationChallengeDoesNotPretendConnected()throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Future<?> remote=executor.submit(()->{try(Socket s=server.accept()){
                s.setSoTimeout(5000);AdbShell.read(s.getInputStream());packet(s,AdbShell.AUTH,1,0,new byte[20]);assertEquals(-1,s.getInputStream().read());
            }catch(Exception e){throw new RuntimeException(e);}});
            try{new AdbShell(new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()),null);fail();}
            catch(IOException expected){assertTrue(expected.getMessage().contains("要求授权"));}
            remote.get(5,TimeUnit.SECONDS);
        }finally{executor.shutdownNow();}
    }
}
