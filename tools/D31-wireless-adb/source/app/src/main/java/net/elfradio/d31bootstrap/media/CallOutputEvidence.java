package net.elfradio.d31bootstrap.media;

import java.util.*;
import java.util.regex.*;

/** 先证明唯一自身MIC再投影；原始Sample不变，PTT解析入口及拒绝输入语义不变。 */
final class CallOutputEvidence {
    static D31OutputEvidence.Result evaluate(AudioCaptureObservation.Sample sample,CallDuplexGuard.Identity output) {
        try {
            String f=sample.flinger.text.replace("\r\n","\n").replace('\r','\n');
            String p=sample.policy.text.replace("\r\n","\n").replace('\r','\n');
            int begin=unique(p,"Inputs dump:\n"),end=unique(p,"Streams dump:\n");
            require(begin<end);
            List<String> input=rows(p.substring(begin+"Inputs dump:\n".length(),end));
            require(input.size()==8);
            Matcher handle=Pattern.compile("- Input ([0-9]+) dump:").matcher(input.get(0));require(handle.matches());
            String io=handle.group(1),id=field(input,"ID:");
            require(id.matches("[1-9][0-9]*")&&field(input,"Ref Count ").equals("1")&&field(input,"Open Ref Count ").equals("1"));
            int patchBegin=unique(p,"Audio Patches:\n"),patchEnd=unique(p,"Voe volume dump:\n");
            require(patchBegin<patchEnd);
            List<String> patches=rows(p.substring(patchBegin+"Audio Patches:\n".length(),patchEnd));
            require(patches.size()==8||patches.size()==16);
            StringBuilder kept=new StringBuilder();Set<String> ids=new HashSet<>(),handles=new HashSet<>(),flingerHandles=new HashSet<>();
            int removed=0,outputs=0;
            for(int i=0;i<patches.size();i+=8){
                List<String> block=patches.subList(i,i+8);
                require(block.get(0).matches("Audio patch [0-9]+:")&&ids.add(block.get(0)));
                String ph=field(block,"- handle:"),fh=field(block,"- audio flinger handle:");
                require(ph.matches("[1-9][0-9]*")&&fh.matches("[1-9][0-9]*")&&handles.add(ph)&&flingerHandles.add(fh));
                require(field(block,"- owner uid:").matches("[0-9]+")&&block.get(4).equals("- 1 sources:")&&block.get(6).equals("- 1 sinks:"));
                Matcher mic=Pattern.compile("- Device ID ([0-9]+) AUDIO_DEVICE_IN_BUILTIN_MIC").matcher(block.get(5));
                if(mic.matches()) {
                    require(++removed==1&&block.get(7).equals("- Mix ID "+id+" I/O handle "+io));
                    List<String> devices=rows(p.substring(unique(p,"Available input devices:\n")+"Available input devices:\n".length(),unique(p,"Outputs dump:\n")));
                    boolean found=false;
                    for(int j=0;j+2<devices.size();j++)if(devices.get(j).matches("Device [0-9]+:")
                            &&devices.get(j+1).equals("- id: "+mic.group(1))&&devices.get(j+2).equals("- type: AUDIO_DEVICE_IN_BUILTIN_MIC")) {
                        require(!found);found=true;
                    }
                    require(found);
                }else {
                    require(++outputs==1&&block.get(5).matches("- Mix ID [0-9]+ I/O handle [0-9]+")
                            &&block.get(7).matches("- Device ID [0-9]+ AUDIO_DEVICE_OUT_SPEAKER"));
                    for(String line:block)kept.append("  ").append(line).append('\n');
                }
            }
            require(removed==1);
            String projectedPolicy=p.substring(0,begin)+"Inputs dump:\n"+p.substring(end,patchBegin)
                    +"Audio Patches:\n"+kept+p.substring(patchEnd);
            StringBuilder projectedFlinger=new StringBuilder();int removedThreads=0;boolean skip=false;
            for(String line:f.split("\n",-1)) {
                if(line.startsWith("Input thread ")){require(!skip);skip=true;removedThreads++;}
                if(!skip)projectedFlinger.append(line).append('\n');
                else if(line.trim().equals("0 Effect Chains"))skip=false;
            }
            require(!skip&&removedThreads==1);
            AudioInputOwnership.Dump fd=new AudioInputOwnership.Dump(projectedFlinger.toString(),sample.flinger.complete,
                    sample.flinger.startedElapsedMs,sample.flinger.finishedElapsedMs);
            AudioInputOwnership.Dump pd=new AudioInputOwnership.Dump(projectedPolicy,sample.policy.complete,
                    sample.policy.startedElapsedMs,sample.policy.finishedElapsedMs);
            long now=Math.max(sample.finished,Math.max(fd.finishedElapsedMs,pd.finishedElapsedMs));
            // 调用者已按真实输入身份严格核对整份原件；只把该唯一输入从单向输出视图移除。
            return output==null?D31OutputEvidence.evaluate(fd,pd,1,now)
                    :D31OutputEvidence.evaluate(fd,pd,D31OutputIdentity.from(output.privateJson()),now);
        }catch(Exception malformed){return new D31OutputEvidence.Result(D31OutputEvidence.State.UNKNOWN,"MEDIA_CALL_PATCH_UNVERIFIED");}
    }
    private static int unique(String text,String key){int at=text.indexOf(key);require(at>=0&&at==text.lastIndexOf(key));return at;}
    private static List<String> rows(String text){List<String> rows=new ArrayList<>();for(String line:text.split("\n"))if(!line.trim().isEmpty())rows.add(line.trim().replaceAll("\\s+"," "));return rows;}
    private static String field(List<String> rows,String key){String value=null;for(String row:rows)if(row.startsWith(key)){require(value==null);value=row.substring(key.length()).trim();}require(value!=null);return value;}
    private static void require(boolean ok){if(!ok)throw new IllegalArgumentException();}
    private CallOutputEvidence(){}
}
