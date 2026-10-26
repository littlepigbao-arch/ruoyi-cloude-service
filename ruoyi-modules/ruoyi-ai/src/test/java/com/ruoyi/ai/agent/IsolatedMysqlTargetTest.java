package com.ruoyi.ai.agent;

import junit.framework.TestCase;

/** Pure guard test; no connection or SQL. */
public class IsolatedMysqlTargetTest extends TestCase {
    public void testB5UrlHasItsOwnDataDirectoryAndOldTargetKeepsItsOriginalGuard(){
        assertEquals("D:/Code/agentReport/b5-db-20261024/mysql-data/",
            A1TestFixture.expectedIsolatedDataDir("jdbc:mysql://127.0.0.1:33359/b5_isolated?useSSL=false"));
        assertEquals("D:/Code/agentReport/a1-output-20261014/mysql-data/",
            A1TestFixture.expectedIsolatedDataDir("jdbc:mysql://127.0.0.1:33357/a1_isolated?useSSL=false"));
        for(String bad:new String[]{"jdbc:mysql://127.0.0.1:3306/ry-cloud?useSSL=false",
                "jdbc:mysql://127.0.0.1:33359/a1_isolated?useSSL=false",
                "jdbc:mysql://127.0.0.1:33357/b5_isolated?useSSL=false",
                "jdbc:mysql://localhost:33359/b5_isolated?useSSL=false"}){
            try{A1TestFixture.expectedIsolatedDataDir(bad);fail("Unsafe MySQL target accepted: "+bad);}
            catch(IllegalStateException expected){assertNotNull(expected.getMessage());}
        }
    }
}
