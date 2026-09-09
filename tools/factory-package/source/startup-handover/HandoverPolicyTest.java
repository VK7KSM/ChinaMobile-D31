import java.util.*;
public final class HandoverPolicyTest {
    static final class Fake implements HandoverPolicy.Ops {
        final List<String> events = new ArrayList<>();
        final Map<String,Integer> states = new HashMap<>();
        final Set<String> mounts = new HashSet<>();
        boolean journal;
        int fail = -1;
        void event(String s) throws Exception {
            events.add(s);
            if (events.size() == fail) throw new Exception("Injected " + s);
        }
        public void journal(Map<String,Integer> s) throws Exception { event("journal"); journal = true; }
        public void state(String pkg, int v) throws Exception { event("state:"+pkg+":"+v); states.put(pkg,v); }
        public void stopped(String pkg) throws Exception { event("stopped:"+pkg); }
        public void mount(String id) throws Exception { event("mount:"+id); mounts.add(id); }
        public void unmount(String id) throws Exception { event("unmount:"+id); mounts.remove(id); }
        public void verify(String id) throws Exception { event("verify:"+id); }
        public void clearJournal() throws Exception { event("clear"); journal=false; }
    }
    static LinkedHashMap<String,Integer> states() {
        LinkedHashMap<String,Integer> s = new LinkedHashMap<>(); s.put("imscc",0); s.put("nexui",1); return s;
    }
    public static void main(String[] args) throws Exception {
        List<String> ids=Arrays.asList("imscc","nexui","tls");
        Fake pass = new Fake(); HandoverPolicy.apply(pass,states(),ids);
        if (pass.journal || !pass.states.equals(states()) || pass.mounts.size()!=3) throw new AssertionError();
        if (pass.events.indexOf("stopped:nexui") > pass.events.indexOf("mount:imscc")) throw new AssertionError();
        if (pass.events.indexOf("state:nexui:1") < pass.events.indexOf("verify:tls")) throw new AssertionError();
        System.out.println("正常交接："+pass.events);
        for (int fail=1;fail<=pass.events.size();fail++) {
            Fake fake = new Fake(); fake.fail=fail;
            try { HandoverPolicy.apply(fake,states(),ids); throw new AssertionError("未抛出故障"); }
            catch (AssertionError e) { throw e; } catch (Exception expected) { }
            if (fail>1 && fail<=11 && (fake.journal || !fake.states.equals(states()) || !fake.mounts.isEmpty())) throw new AssertionError(fake.events);
            if (fail>=12 && !fake.journal) throw new AssertionError("恢复失败必须留存记录");
            System.out.println("故障位置"+fail+"："+fake.events);
        }
        Fake disabled=new Fake(); LinkedHashMap<String,Integer> state=states(); state.put("nexui",2);
        try { HandoverPolicy.apply(disabled,state,ids); throw new AssertionError(); }
        catch (IllegalStateException expected) { if (!disabled.events.isEmpty()) throw new AssertionError(); }
        System.out.println("状态机测试通过：16项");
        LinkedHashMap<String,Integer> cell = new LinkedHashMap<>();
        cell.put("imscc", 0); cell.put("getnumber", 0); cell.put("nexui", 0);
        List<String> cellIds = Arrays.asList("imscc", "nexui", "tls", "getnumber", "cellular-flags");
        Fake cellPass = new Fake(); HandoverPolicy.apply(cellPass, cell, cellIds);
        if (cellPass.journal || !cellPass.states.equals(cell) || cellPass.mounts.size() != 5) throw new AssertionError();
        int firstRestore = cellPass.events.indexOf("state:imscc:0") + 1;
        if (cellPass.events.indexOf("stopped:getnumber") > cellPass.events.indexOf("mount:imscc") ||
            cellPass.events.indexOf("verify:cellular-flags") >= firstRestore - 1) throw new AssertionError();
        for (int fail = 1; fail <= cellPass.events.size(); fail++) {
            Fake fake = new Fake(); fake.fail = fail;
            try { HandoverPolicy.apply(fake, cell, cellIds); throw new AssertionError(); }
            catch (AssertionError e) { throw e; } catch (Exception expected) { }
            if (fail > 1 && fail < firstRestore && (fake.journal || !fake.states.equals(cell) || !fake.mounts.isEmpty())) throw new AssertionError(fake.events);
            if (fail >= firstRestore && !fake.journal) throw new AssertionError("蜂窝恢复失败必须保留记录");
        }
        System.out.println("蜂窝交接策略测试通过：" + (cellPass.events.size() + 1) + "项");
    }
}
