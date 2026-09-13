package net.elfradio.d31bootstrap.media;

import java.util.*;
import java.util.regex.*;

/** 仅覆盖D31零PCM首轮已见形状；客户端s16/mono与混音HAL pcm32/stereo分别核对。 */
final class D31ActiveOutputEvidence {
    private static final String HEADER="Name Active Client Type Fmt Chn mask Session fCount S F SRate L dB R dB Server Main buf Aux Buf Flags UndFrmCnt";
    private static final class Output {
        int handle,active,rate,format,mask,device,flags;
        final int[] streams=new int[15];
    }
    static D31OutputEvidence.Result evaluate(String flinger,String policy,D31OutputIdentity own){
        return evaluate(flinger,policy,own,null);
    }
    /** 仅证明目标stream归属；其它流保留给外部占用判断，不代表SELF_ONLY全局空闲。 */
    static boolean ownsStream(String flinger,String policy,D31OutputIdentity own,org.json.JSONObject external){
        return own!=null&&external!=null&&evaluate(flinger,policy,own,external).state==D31OutputEvidence.State.SELF_ONLY;
    }
    private static D31OutputEvidence.Result evaluate(String flinger,String policy,D31OutputIdentity own,org.json.JSONObject external){
        boolean streamOnly=external!=null;
        if(own!=null&&!own.supported())return unknown("MEDIA_OUTPUT_IDENTITY_FORMAT_UNVERIFIED");
        String stage="FLINGER";
        try{
            List<String> f=lines(flinger),p=lines(policy);Map<Integer,Output> outputs=new HashMap<>();
            int active=0,ownedHandle=-1,ownedPort=-1,warm=0;boolean starting=false;
            for(int i=0;i<f.size();i++)if(f.get(i).startsWith("Output thread ")){
                require(f.get(i).matches("Output thread 0x[0-9a-fA-F]+ type 0 \\(MIXER\\):"));
                int end=i+1;while(end<f.size()&&!f.get(end).equals("0 Effect Chains"))end++;
                require(end<f.size());List<String> block=f.subList(i+1,end);Output out=new Output();
                out.handle=decimal(field(block,"I/O handle:"));require(out.handle>0&&!outputs.containsKey(out.handle));
                int table=-1,total=-1;
                for(int row=0;row<block.size();row++){
                    String line=block.get(row);Matcher count=Pattern.compile("([0-9]+) Tracks of which ([0-9]+) are active").matcher(line);
                    if(line.equals("0 Tracks")){require(table<0);table=row;total=0;}
                    else if(count.matches()){require(table<0);table=row;total=decimal(count.group(1));out.active=decimal(count.group(2));}
                    else require(!line.contains("Effect Chains"));
                }
                require(table>=0&&total>=0&&total<=64&&out.active<=total);
                String standby=field(block,"Standby:");require(standby.equals("yes")||standby.equals("no"));
                if(standby.equals("no")||out.active>0){warm++;ownedHandle=out.handle;}
                int counted=0;Set<Integer> names=new HashSet<>();
                if(total==0)require(table+1==block.size());
                else{
                    require(table+2+total==block.size()&&block.get(table+1).replaceAll("\\s+"," ").equals(HEADER));
                    for(int row=table+2;row<block.size();row++){
                        String[] c=block.get(row).split("\\s+");require(c.length==18&&names.add(decimal(c[0])));
                        require(c[1].equals("yes")||c[1].equals("no"));
                        for(int col:new int[]{2,3,6,7,9,10,17})decimal(c[col]);hex(c[4]);hex(c[5]);
                        if(c[1].equals("no"))continue;
                        counted++;
                        int stream=decimal(c[3]);if(streamOnly){require(stream<out.streams.length);out.streams[stream]++;}
                        if(streamOnly&&own!=null&&stream!=own.stream){
                            require(decimal(c[2])>0&&decimal(c[6])>0&&decimal(c[7])>0&&decimal(c[10])>0);
                            require(c[8].equals("A")&&c[9].equals("3")&&standby.equals("no"));
                            require(c[11].matches("-?(?:[0-9]+(?:\\.[0-9]+)?|inf)")&&c[12].matches("-?(?:[0-9]+(?:\\.[0-9]+)?|inf)"));
                            require(c[13].matches("[0-9a-fA-F]{8}")&&c[14].matches("0x[0-9a-fA-F]+")&&c[15].equals("0x0")
                                    &&(c[16].equals("0x000")||c[16].equals("0x001")));
                            continue;
                        }
                        active++;
                        if(own==null)return unknown("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED");
                        if(decimal(c[2])!=own.pid||decimal(c[6])!=own.session||decimal(c[3])!=own.stream)
                            return busy("MEDIA_OUTPUT_OTHER_ACTIVE");
                        require(hex(c[4])==1&&hex(c[5])==1&&decimal(c[10])==own.rate);
                        require(decimal(c[7])>0&&decimal(c[7])<=1048576);
                        boolean priming=c[8].equals("R")&&c[9].equals("1")&&standby.equals("yes")
                                &&c[13].equals("00000000")&&c[16].equals("0x000")&&decimal(c[17])==0;
                        require(priming||(c[8].equals("A")&&c[9].equals("3")&&standby.equals("no")));
                        starting|=priming;
                        require(c[11].matches("-?(?:[0-9]+(?:\\.[0-9]+)?|inf)")&&c[12].matches("-?(?:[0-9]+(?:\\.[0-9]+)?|inf)"));
                        // AOSP mCblk->mFlags：仅欠载通知位不改变身份，INVALID/DISABLED及其它位仍拒绝。
                        require(c[13].matches("[0-9a-fA-F]{8}")&&c[14].matches("0x[0-9a-fA-F]+")&&c[15].equals("0x0")
                                &&(c[16].equals("0x000")||c[16].equals("0x001")));
                        ownedHandle=out.handle;
                    }
                }
                require(counted==out.active);outputs.put(out.handle,out);
                if(standby.equals("no")||out.active>0){
                    require(field(block,"Sample rate:").equals("48000 Hz"));out.rate=48000;
                    require(field(block,"HAL format:").equals("0x3 (pcm32)")&&field(block,"Format:").equals("0x3 (pcm32)"));out.format=3;
                    require(field(block,"Channel count:").equals("2")&&field(block,"Channel mask:").equals("0x00000003 (front-left, front-right)"));out.mask=3;
                    require(field(block,"Frame size:").equals("8 bytes")&&field(block,"Output device:").equals("0x2 (SPEAKER)"));out.device=2;
                    require(field(block,"Input device:").equals("0 (NONE)")&&field(block,"Audio source:").equals("0 (default)"));
                    require(field(block,"mAFSuspend :").equals("0")&&field(block,"Suspend count:").equals("0"));
                    if(!field(block,"Pending config events:").equals("none"))return unknown("MEDIA_OUTPUT_CONFIG_PENDING");
                    require(unique(block,"FastMixer not initialized")>=0);
                    require(field(block,"AudioStreamOut:").matches("0x[0-9a-fA-F]+ flags 0x2 \\(PRIMARY\\)"));out.flags=2;
                }
                i=end;
            }
            if(active>1||warm!=1)return unknown("MEDIA_OUTPUT_ACTIVE_COUNT_UNVERIFIED");
            stage="REFERENCES";
            int refs=unique(f,"Global session refs:"),header=unique(f,"session pid count"),hardware=unique(f,"Hardware status: 0");
            require(refs<header&&header<hardware);int matched=0;
            for(int row=header+1;row<hardware;row++){
                String[] c=f.get(row).split("\\s+");require(c.length==3);int session=decimal(c[0]),pid=decimal(c[1]),count=decimal(c[2]);
                if(active==1&&session==own.session){require(pid==own.pid&&count==1);matched++;}
            }
            if(active==1)require(matched==1);
            stage="POLICY";
            int begin=unique(p,"Outputs dump:"),end=unique(p,"Inputs dump:");Set<Integer> handles=new HashSet<>();
            require(begin<end);
            for(int i=begin+1;i<end;){
                Matcher m=Pattern.compile("- Output ([0-9]+) dump:").matcher(p.get(i));require(m.matches());
                int handle=decimal(m.group(1));require(handles.add(handle)&&outputs.containsKey(handle));
                int next=i+1;while(next<end&&!p.get(next).startsWith("- Output "))next++;
                List<String> block=p.subList(i+1,next);int table=unique(block,"Stream volume refCount muteCount");require(table+16==block.size());
                Output out=outputs.get(handle);
                if(out.rate>0){
                    ownedPort=decimal(field(block,"ID:"));
                    require(decimal(field(block,"Sampling rate:"))==out.rate&&hex(field(block,"Format:"))==out.format);
                    require(hex(field(block,"Channels:"))==out.mask&&hex(field(block,"Devices "))==out.device&&hex(field(block,"Flags "))==out.flags);
                }
                for(int stream=0;stream<15;stream++){
                    String[] c=block.get(table+1+stream).split("\\s+");require(c.length==4&&c[0].equals(String.format(Locale.ROOT,"%02d",stream)));
                    require(c[1].matches("-?[0-9]+\\.[0-9]+")&&c[2].matches("[0-9]{2}")&&c[3].matches("[0-9]{2}"));
                    boolean self=active==1&&handle==ownedHandle&&stream==own.stream;
                    require(decimal(c[2])==(streamOnly?out.streams[stream]:(self?1:0)));if(self)require(decimal(c[3])==0);
                }
                i=next;
            }
            require(handles.equals(outputs.keySet()));
            if(streamOnly){
                org.json.JSONArray rows=external.getJSONObject("audio").getJSONArray("streams");
                for(Output out:outputs.values())for(int stream=0;stream<out.streams.length;stream++)if(out.streams[stream]>0){
                    org.json.JSONObject row=rows.getJSONObject(stream);
                    require(row.opt("stream") instanceof Integer&&row.getInt("stream")==stream&&Boolean.TRUE.equals(row.opt("active")));
                }
            }
            stage="PATCH";
            List<String> patch=p.subList(unique(p,"Audio Patches:")+1,unique(p,"Voe volume dump:"));
            require(patch.size()==8&&patch.get(0).matches("Audio patch [0-9]+:")&&patch.get(1).matches("- handle: [0-9]+")
                    &&patch.get(2).matches("- audio flinger handle: [0-9]+")&&patch.get(3).matches("- owner uid: [0-9]+"));
            require(patch.get(4).equals("- 1 sources:")&&patch.get(6).equals("- 1 sinks:"));
            Matcher source=Pattern.compile("- Mix ID ([0-9]+) I/O handle ([0-9]+)").matcher(patch.get(5));require(source.matches());
            require(decimal(source.group(1))==ownedPort&&decimal(source.group(2))==ownedHandle);
            Matcher sink=Pattern.compile("- Device ID ([0-9]+) AUDIO_DEVICE_OUT_SPEAKER").matcher(patch.get(7));require(sink.matches());
            List<String> devices=p.subList(unique(p,"Available output devices:")+1,unique(p,"Available input devices:"));
            require(devices.size()==3&&devices.get(0).matches("Device [0-9]+:")&&decimal(field(devices,"- id:"))==decimal(sink.group(1))
                    &&field(devices,"- type:").equals("AUDIO_DEVICE_OUT_SPEAKER"));
            if(starting)return new D31OutputEvidence.Result(D31OutputEvidence.State.STARTING,"MEDIA_OUTPUT_STARTING");
            return new D31OutputEvidence.Result(active==1?D31OutputEvidence.State.SELF_ONLY:D31OutputEvidence.State.EMPTY,
                    active==1?"MEDIA_OUTPUT_IDENTITY_AND_POLICY_MATCHED":"MEDIA_OUTPUT_RELEASED_TRACKS_OBSERVED");
        }catch(Exception invalid){return unknown("MEDIA_OUTPUT_"+stage+"_CONTRACT_MISMATCH");}
    }
    private static List<String> lines(String text){
        List<String> rows=new ArrayList<>();for(String line:text.replace('\r','\n').split("\n"))if(!line.trim().isEmpty())rows.add(line.trim().replaceAll("\\s+"," "));return rows;
    }
    private static int unique(List<String> rows,String value){int i=rows.indexOf(value);require(i>=0&&i==rows.lastIndexOf(value));return i;}
    private static String field(List<String> rows,String prefix){String value=null;for(String row:rows)if(row.startsWith(prefix)){require(value==null);value=row.substring(prefix.length()).trim();}require(value!=null);return value;}
    private static int decimal(String value){require(value.matches("[0-9]{1,10}"));return Integer.parseInt(value);}
    private static int hex(String value){require(value.matches("[0-9a-fA-F]{8}"));return Integer.parseInt(value,16);}
    private static void require(boolean ok){if(!ok)throw new IllegalArgumentException();}
    private static D31OutputEvidence.Result unknown(String reason){return new D31OutputEvidence.Result(D31OutputEvidence.State.UNKNOWN,reason);}
    private static D31OutputEvidence.Result busy(String reason){return new D31OutputEvidence.Result(D31OutputEvidence.State.BUSY,reason);}
    private D31ActiveOutputEvidence(){}
}
