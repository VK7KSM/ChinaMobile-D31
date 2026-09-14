import java.util.*;
public class HandoverRuntime {
    static final Map<String,String> values=new HashMap<>();
    static final Set<String> whitelist=new LinkedHashSet<>();
    static int writes;
    static String ignore="";
    static String command(String... a) {
        if(a[0].endsWith("settings")) {
            String key=a[2]+":"+a[3];
            if(a[1].equals("put")) {
                writes++;
                if(ignore.equals(a[3]) || ignore.equals(a[3]+":"+a[4]))return "";
                if(a[3].equals("location_providers_allowed") && a[4].startsWith("+")) {
                    Set<String> entries=new LinkedHashSet<>();
                    String old=values.getOrDefault(key,"");
                    if(!old.isEmpty() && !old.equals("null"))entries.addAll(Arrays.asList(old.split(",")));
                    entries.add(a[4].substring(1));
                    values.put(key,String.join(",",entries));
                } else values.put(key,a[4]);
                return "";
            }
            return values.getOrDefault(key,"null");
        }
        if(a[0].endsWith("appops")) {
            String key=a[2]+":"+a[3];
            if(a[1].equals("set")) {writes++;values.put(key,a[4]);return "";}
            return a[3]+": "+values.getOrDefault(key,"default");
        }
        if(a[0].endsWith("dumpsys") && a[1].equals("deviceidle")) {
            if(a.length==4) {writes++;whitelist.add(a[3].substring(1));return "";}
            StringBuilder result=new StringBuilder();
            for(String pkg:whitelist)result.append("user,").append(pkg).append(",10001\n");
            return result.toString();
        }
        throw new AssertionError(Arrays.toString(a));
    }
}
