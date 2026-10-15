package com.ruoyi.ai.service;

import com.ruoyi.ai.domain.dto.*;
import junit.framework.TestCase;
import java.util.*;

public class ActionTargetCompatibilityTest extends TestCase {
    private WorkbookContext context() {
        WorkbookContext c=new WorkbookContext();c.setActiveSheetId("b");c.setActiveSheetName("Arrivals");
        SheetContext a=new SheetContext();a.setSheetId("a");a.setSheetName("Orders");
        SheetContext b=new SheetContext();b.setSheetId("b");b.setSheetName("Arrivals");c.setSheets(Arrays.asList(a,b));return c;
    }
    private Action action(String version,String id) {
        Action a=new Action();a.setType("setCell");a.setSchemaVersion(version);a.setRange("A1");
        if(id!=null){Action.Target t=new Action.Target();t.setSheetId(id);t.setRange("A1");a.setTarget(t);}return a;
    }
    private void reject(Action action) {try{ActionTargetValidator.normalize(action,context());fail("Unsafe target accepted");}catch(IllegalArgumentException expected){assertNotNull(expected.getMessage());}}
    public void testV2NeverDefaultsToActiveSheet(){reject(action("2.0",null));reject(action("2.0","missing"));Action a=action("2.0",null);a.setSheetId("a");reject(a);}
    public void testV2ConflictingIdAndNameRejected(){Action a=action("2.0","a");a.setSheetId("b");reject(a);a=action("2.0","a");a.getTarget().setSheetName("Arrivals");reject(a);}
    public void testV2PrefixConflictRejected(){Action a=action("2.0","a");a.setRange(null);a.getTarget().setRange("Arrivals!A1");reject(a);}
    public void testValidV2RetainsExplicitTarget(){Action a=action("2.0","a");ActionTargetValidator.normalize(a,context());assertEquals("a",a.getTarget().getSheetId());assertEquals("Orders",a.getTarget().getSheetName());}
    public void testV1ActiveAndNamedTargetsCompatible(){Action a=action("1.0",null);ActionTargetValidator.normalize(a,context());assertEquals("b",a.getTarget().getSheetId());a=action(null,null);a.setRange("'Orders'!A1");ActionTargetValidator.normalize(a,context());assertEquals("a",a.getTarget().getSheetId());assertEquals("A1",a.getTarget().getRange());}
    public void testUnknownProtocolRejected(){reject(action("9.0","a"));}
}
