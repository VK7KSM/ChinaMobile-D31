package net.elfradio.d31bootstrap;

import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreparedFailureProjectionTest {
    private JSONObject project(JSONObject value)throws Exception {
        Method method=RemoteMediaSessions.class.getDeclaredMethod("diagnostics",JSONObject.class);
        method.setAccessible(true);return (JSONObject)method.invoke(null,value);
    }
    @Test public void existingRootDiagnosticsRetainsOnlyFixedFailureFields()throws Exception {
        JSONObject result=project(new JSONObject().put("failed_stage","BEFORE_ACTIVATE").put("exception_type","IOException")
                .put("message","PRIVATE_TOKEN").put("path","PRIVATE_PATH").put("input_identity","PRIVATE_IDENTITY"));
        assertEquals("BEFORE_ACTIVATE",result.getString("failed_stage"));assertFalse(result.has("exception_type"));
        assertEquals(1,result.length());assertFalse(result.toString().contains("PRIVATE"));
    }
    @Test public void arbitraryStagesAndTypesAreNotPassedThrough()throws Exception {
        JSONObject result=project(new JSONObject().put("failed_stage","PRIVATE_PATH").put("exception_type","PrivateOwnerException"));
        assertEquals(0,result.length());
        assertEquals("MEDIA_PREPARED_PEER_FAILED",project(new JSONObject().put("failed_stage","MEDIA_PREPARED_PEER_FAILED")).getString("failed_stage"));
    }
}
