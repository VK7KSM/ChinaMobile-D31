package net.elfradio.d31bootstrap.media;

import org.json.JSONObject;

/** 全部客户、会话、指针为虚构值；按既有输入与输出列组合，无真实采音。 */
final class CallDuplexFixtures {
    static final int PID=41001,IN=42;
    static final String INPUT="Input thread 0xdef type 3 (RECORD):\n"
            +"  I/O handle: 18\n  Standby: no\n  Sample rate: 16000 Hz\n  Channel count: 1\n"
            +"  Channel mask: 0x00000010 (front)\n  Format: 0x1 (pcm16)\n"
            +"  Input device: 0x80000004 (BUILTIN_MIC)\n  Audio source: 1 (mic)\n"
            +"  Fast capture thread: no\n  Fast track available: no\n  FastCapture not initialized\n"
            +"  1 Tracks of which 1 are active\n    Active Client Fmt Chn mask Session S Server fCount SRate\n"
            +"    yes 41001 1 00000010 42 6 00003FC0 1024 16000\n  0 Effect Chains\n";
    static final String POLICY_INPUT="- Input 18 dump:\n ID: 8\n Sampling rate: 16000\n Format: 1\n Channels: 00000010\n"
            +" Devices 80000004\n Ref Count 1\n Open Ref Count 1\n";
    static final String PATCH="  Audio patch 2:\n  - handle: 91\n  - audio flinger handle: 92\n  - owner uid: 1000\n"
            +"  - 1 sources:\n    - Device ID 9 AUDIO_DEVICE_IN_BUILTIN_MIC\n  - 1 sinks:\n    - Mix ID 8 I/O handle 18\n";
    static class Time implements MediaCapture.Clock {volatile long now=1040;public long elapsed(){return now;}public long wall(){return now;}}
    static CallDuplexGuard.Identity input()throws Exception{return new CallDuplexGuard.Identity(true,PID,IN,16000,1,2,1);}
    static CallDuplexGuard.Identity output()throws Exception{return new CallDuplexGuard.Identity(false,PID,7101,48000,1,2,3);}
    static String flinger(boolean in,boolean out){String f=D31OutputFixtures.flinger(out);
        return in?f.replace("usb_hw",INPUT+"usb_hw").replace("Hardware status:"," 42 41001 1\nHardware status:"):f;}
    static String policy(boolean in,boolean out){String p=D31OutputFixtures.policy(out);
        return in?p.replace("Inputs dump:\n","Inputs dump:\n"+POLICY_INPUT)
                .replace("Available input devices:\n","Available input devices:\n Device 9:\n - id: 9\n - type: AUDIO_DEVICE_IN_BUILTIN_MIC\n")
                .replace("Voe volume dump:",PATCH+"Voe volume dump:"):p;}
    static JSONObject external(boolean in,boolean out,int focus)throws Exception{JSONObject e=D31OutputFixtures.idle(focus);
        e.getJSONObject("audio").getJSONArray("sources").getJSONObject(1).put("active",in);
        e.getJSONObject("audio").getJSONArray("streams").getJSONObject(3).put("active",out);return e;}
    static AudioCaptureObservation.Sample sample(String f,String p,JSONObject e,long began){return new AudioCaptureObservation.Sample(
            new AudioInputOwnership.Dump(f,true,began,began+10),new AudioInputOwnership.Dump(p,true,began+11,began+20),e,began+21,began+30);}
    static AudioCaptureObservation.Sample sample(boolean in,boolean out,int focus,long began)throws Exception{return sample(flinger(in,out),policy(in,out),external(in,out,focus),began);}
    private CallDuplexFixtures(){}
}
