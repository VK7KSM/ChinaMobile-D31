import java.io.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public final class FactoryDefaultsTest {
    static Document parse(String text) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes("UTF-8")));
    }
    static void check(boolean value) { if(!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Document empty=parse("<map/>");
        FactoryInit.disableTcpAcceleration(empty);
        check(empty.getElementsByTagName("boolean").getLength()==1);
        check(((Element)empty.getElementsByTagName("boolean").item(0)).getAttribute("value").equals("false"));
        Document existing=parse("<map><string name='user'>preserve-test-value</string><boolean name='TcpAcclerate' value='true'/></map>");
        FactoryInit.disableTcpAcceleration(existing);
        FactoryInit.disableTcpAcceleration(existing);
        check(existing.getElementsByTagName("boolean").getLength()==1);
        check(existing.getElementsByTagName("string").item(0).getTextContent().equals("preserve-test-value"));
        for(String bad:new String[]{"<wrong/>","<map><string name='TcpAcclerate'>true</string></map>","<map><boolean name='TcpAcclerate'/><boolean name='TcpAcclerate'/></map>"}) {
            try { FactoryInit.disableTcpAcceleration(parse(bad)); throw new AssertionError(); } catch(IOException expected) { }
        }
        check(FactoryInit.addService("null","guard").equals("guard"));
        check(FactoryInit.addService("existing","guard").equals("existing:guard"));
        check(FactoryInit.addService("existing:guard","guard").equals("existing:guard"));
        System.out.println("首次设置：新建、保留其它字段、幂等、无效输入拒绝、服务合并通过");
    }
}
