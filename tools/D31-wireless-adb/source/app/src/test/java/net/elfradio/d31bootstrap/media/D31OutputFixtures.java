package net.elfradio.d31bootstrap.media;

import org.json.JSONObject;
import org.json.JSONArray;

/** 按已观察列结构重新构造的夹具；全部客户、会话、地址及时间为虚构值。 */
final class D31OutputFixtures {
    static final int PID=41001,SESSION=7101;
    static class Time implements MediaCapture.Clock {long now;public long elapsed(){return now;}public long wall(){return now;}}
    static JSONObject idle(int focus)throws Exception{
        JSONArray streams=new JSONArray(),sources=new JSONArray();
        for(int i=0;i<10;i++)streams.put(new JSONObject().put("stream",i).put("active",false).put("remote_active",false));
        for(int i=0;i<9;i++)sources.put(new JSONObject().put("source",i).put("active",false));
        return new JSONObject().put("audio",new JSONObject().put("mode",0).put("focus_gain",focus).put("streams",streams).put("sources",sources))
                .put("cellular",new JSONObject().put("call_state",0).put("phone_count",1))
                .put("nexui",new JSONObject().put("resolved",true).put("statuses",new JSONArray().put("IDLE")));
    }
    static JSONObject identity()throws Exception{return new JSONObject().put("pid",PID).put("audio_session",SESSION)
            .put("stream_type",3).put("sample_rate",48000).put("channels",1).put("audio_format",2);}
    static String flinger(boolean active){
        return "mAFSuspend: 0\nmMicMute: 0\nClients:\n  42001\nNotification Clients:\n  42001\nGlobal session refs:\n  session   pid count\n"
                +"  7102 42001 1\n  7103 42001 1\n"+(active?"  7101 41001 1\n":"")+"Hardware status: 0\nStandby Time mSec: 3000\n"
                +output(2,active)+output(4,false)+"usb_hw version 2.2.0\nReroute submix audio module:\n"+routes();
    }
    private static String routes(){String text="";for(int i=0;i<10;i++)text+=" route["+i+"] rate in=0 out=0, addr=[]\n";return text;}
    private static String output(int handle,boolean active){
        int inactive=handle==2?3:2;
        String text="Output thread 0x1000"+handle+"000 type 0 (MIXER):\n  I/O handle: "+handle+"\n  Standby: "+(active?"no":"yes")
                +"\n  Sample rate: 48000 Hz\n  HAL format: 0x3 (pcm32)\n  Format: 0x3 (pcm32)\n  Channel count: 2\n"
                +"  Channel mask: 0x00000003 (front-left, front-right)\n  Frame size: 8 bytes\n  Output device: "+(active?"0x2 (SPEAKER)":"0 (NONE)")
                +"\n  Input device: 0 (NONE)\n  Audio source: 0 (default)\n  mAFSuspend : 0\n  Suspend count: 0\n"
                +"  Pending config events: none\n  FastMixer not initialized\n  AudioStreamOut: 0x10008000 flags 0x2 (PRIMARY)\n"
                +"  "+(inactive+(active?1:0))+" Tracks of which "+(active?1:0)+" are active\n"
                +"    Name Active Client Type Fmt Chn mask Session fCount S F SRate L dB R dB Server Main buf Aux Buf Flags UndFrmCnt\n";
        for(int i=0;i<inactive;i++)text+="    "+i+" no 42001 3 00000001 00000001 "+(7102+i)+" 4104 S 1 48000 0 0 00000000 0x10008000 0x0 0x601 0\n";
        if(active)text+=activeRow();return text+"  0 Effect Chains\n";
    }
    static String activeRow(){return "    3 yes 41001 3 00000001 00000001 7101 4800 A 3 48000 -inf -inf 00000000 0x10008000 0x0 0x000 0\n";}
    static String policy(boolean active){return "AudioPolicyManager Dump: 0x10000000\nAvailable output devices:\n  Device 1:\n  - id: 1\n"
            +"  - type: AUDIO_DEVICE_OUT_SPEAKER\nAvailable input devices:\nOutputs dump:\n"+policyOutput(2,active)+policyOutput(4,false)
            +"Inputs dump:\nStreams dump:\nRegistered effects:\nAudio Patches:\n  Audio patch 1:\n  - handle: 81\n  - audio flinger handle: 82\n"
            +"  - owner uid: 1000\n  - 1 sources:\n    - Mix ID 2 I/O handle 2\n  - 1 sinks:\n    - Device ID 1 AUDIO_DEVICE_OUT_SPEAKER\n"
            +"Voe volume dump:\n  0x00000030 0 10 5 28 bluetooth-sco\n";}
    private static String policyOutput(int handle,boolean active){
        String text="- Output "+handle+" dump:\n Latency: 10\n Flags 0000000"+(handle==2?2:4)+"\n ID: "+(handle==2?2:3)
                +"\n Sampling rate: 48000\n Format: 00000003\n Channels: 00000003\n Devices 00000002\n Stream volume refCount muteCount\n";
        for(int i=0;i<15;i++)text+=String.format(java.util.Locale.ROOT," %02d -20.000 %02d %02d\n",i,active&&i==3?1:0,i==9?1:0);return text;
    }
    static AudioInputOwnership.Dump dump(String text){return new AudioInputOwnership.Dump(text,true,1000,1020);}
    private D31OutputFixtures(){}
}
