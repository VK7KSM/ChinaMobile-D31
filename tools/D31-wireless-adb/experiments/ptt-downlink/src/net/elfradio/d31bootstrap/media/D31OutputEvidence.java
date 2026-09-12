package net.elfradio.d31bootstrap.media;

import java.util.*;
import java.util.regex.*;

/** 只接受D31完整空闲输出格式；活动输出尚无完整原件，绝不授予自身豁免。 */
public final class D31OutputEvidence {
    public enum State { EMPTY, BUSY, UNKNOWN }
    public static final class Result {
        public final State state;public final String reason;
        Result(State state,String reason){this.state=state;this.reason=reason;}
    }
    public static Result evaluate(AudioInputOwnership.Dump flinger,AudioInputOwnership.Dump policy,int pid,long now){
        AudioInputOwnership.Result input=AudioInputOwnership.evaluate(flinger,policy,pid,1,now);
        if(input.state==AudioInputOwnership.State.UNKNOWN)return unknown("MEDIA_OUTPUT_BASE_"+input.reason);
        if(input.state!=AudioInputOwnership.State.NO_ACTIVE_INPUT)return new Result(State.BUSY,"MEDIA_PTT_INPUT_ACTIVE");
        try{
            List<String> f=lines(flinger.text),p=lines(policy.text);
            Set<Integer> outputs=new HashSet<>();
            for(int i=0;i<f.size();i++)if(f.get(i).startsWith("Output thread ")){
                int end=i+1;while(end<f.size()&&!f.get(end).equals("0 Effect Chains"))end++;
                if(end==f.size())throw new IllegalArgumentException();
                List<String> block=f.subList(i+1,end);
                if(!outputs.add(Integer.parseInt(field(block,"I/O handle:"))))throw new IllegalArgumentException();
                int table=-1,total=-1;
                for(int n=0;n<block.size();n++){
                    String line=block.get(n);
                    if(line.equals("0 Tracks")){if(table!=-1)throw new IllegalArgumentException();table=n;total=0;}
                    Matcher m=Pattern.compile("([0-9]+) Tracks of which ([0-9]+) are active").matcher(line);
                    if(m.matches()){
                        if(table!=-1)throw new IllegalArgumentException();table=n;total=Integer.parseInt(m.group(1));
                        if(Integer.parseInt(m.group(2))>0)return unknown("MEDIA_OUTPUT_ACTIVE_SHAPE_UNVERIFIED");
                    }
                }
                if(total<0||total>64||!field(block,"Standby:").equals("yes"))throw new IllegalArgumentException();
                if(total==0){if(table+1!=block.size())throw new IllegalArgumentException();}
                else{
                    if(table+2+total!=block.size()||!block.get(table+1).replaceAll("\\s+"," ").equals(
                            "Name Active Client Type Fmt Chn mask Session fCount S F SRate L dB R dB Server Main buf Aux Buf Flags UndFrmCnt"))
                        throw new IllegalArgumentException();
                    for(int row=table+2;row<block.size();row++){
                        String[] cells=block.get(row).split("\\s+");
                        if(cells.length!=18||!cells[1].equals("no"))throw new IllegalArgumentException();
                        for(int col:new int[]{0,2,3,6,7,9,10,17})if(!cells[col].matches("[0-9]{1,10}"))throw new IllegalArgumentException();
                        if(!cells[4].matches("[0-9a-fA-F]{8}")||!cells[5].matches("[0-9a-fA-F]{8}"))throw new IllegalArgumentException();
                    }
                }
                i=end;
            }
            int begin=unique(p,"Outputs dump:"),end=unique(p,"Inputs dump:");
            Set<Integer> policyOutputs=new HashSet<>();
            for(int i=begin+1;i<end;){
                Matcher header=Pattern.compile("- Output ([0-9]+) dump:").matcher(p.get(i));
                if(!header.matches()||!policyOutputs.add(Integer.parseInt(header.group(1))))throw new IllegalArgumentException();
                int next=i+1;while(next<end&&!p.get(next).startsWith("- Output "))next++;
                List<String> block=p.subList(i+1,next);
                int table=unique(block,"Stream volume refCount muteCount");
                if(block.size()-table-1!=15)throw new IllegalArgumentException();
                for(int n=0;n<15;n++){
                    String[] cells=block.get(table+1+n).split("\\s+");
                    if(cells.length!=4||!cells[0].equals(String.format(java.util.Locale.ROOT,"%02d",n)))throw new IllegalArgumentException();
                    if(!cells[2].equals("00"))return unknown("MEDIA_OUTPUT_POLICY_ACTIVE_REFERENCE_UNVERIFIED");
                    if(!cells[1].matches("-?[0-9]+\\.[0-9]+")||!cells[3].matches("[0-9]{2}"))throw new IllegalArgumentException();
                }
                i=next;
            }
            if(outputs.isEmpty()||!outputs.equals(policyOutputs))return unknown("MEDIA_OUTPUT_HANDLES_DISAGREE");
            return new Result(State.EMPTY,"MEDIA_OUTPUT_EMPTY_OBSERVED");
        }catch(Exception malformed){return unknown("MEDIA_OUTPUT_FORMAT_UNVERIFIED");}
    }
    private static List<String> lines(String text){
        List<String> result=new ArrayList<>();
        for(String line:text.replace('\r','\n').split("\n"))if(!line.trim().isEmpty())result.add(line.trim());
        return result;
    }
    private static int unique(List<String> lines,String value){
        int at=lines.indexOf(value);if(at<0||at!=lines.lastIndexOf(value))throw new IllegalArgumentException();return at;
    }
    private static String field(List<String> lines,String prefix){
        String found=null;for(String line:lines)if(line.startsWith(prefix)){
            if(found!=null)throw new IllegalArgumentException();found=line.substring(prefix.length()).trim();
        }if(found==null)throw new IllegalArgumentException();return found;
    }
    private static Result unknown(String reason){return new Result(State.UNKNOWN,reason);}
    private D31OutputEvidence(){}
}
