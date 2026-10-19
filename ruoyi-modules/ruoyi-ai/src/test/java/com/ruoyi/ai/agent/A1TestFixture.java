package com.ruoyi.ai.agent;

import com.ruoyi.ai.mapper.*;
import com.ruoyi.ai.domain.dto.WorkingStateRequest;
import com.ruoyi.ai.service.impl.WorkbookVersionServiceImpl;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.R;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.lang.reflect.Field;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Test-only wiring. Refuses any database except the fresh local A1 instance. */
public class A1TestFixture {
    public final JdbcTemplate db;
    public final AiWorkbookMapper workbooks;
    public final WorkbookVersionMapper versions;
    public final AgentWriteGuard guard;
    public final WorkbookVersionServiceImpl service;
    public final TransactionTemplate tx;
    public final AgentRunService runs;
    public A1TestFixture() throws Exception {
        if(System.getProperty("a1.classpath.output")!=null){
            Set<String> entries=new LinkedHashSet<>();
            for(Class<?> type:new Class<?>[]{A1TestFixture.class,AgentRunService.class,SecurityContextHolder.class,
                com.ruoyi.common.security.utils.SecurityUtils.class,com.ruoyi.common.core.domain.R.class,com.ruoyi.system.api.model.LoginUser.class})
                entries.add(Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            Enumeration<java.net.URL> manifests=getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
            while(manifests.hasMoreElements()){
                java.net.URL resource=manifests.nextElement();
                if("jar".equals(resource.getProtocol()))entries.add(Paths.get(((java.net.JarURLConnection)resource.openConnection()).getJarFileURL().toURI()).toString());
            }
            for(ClassLoader loader=getClass().getClassLoader();loader!=null;loader=loader.getParent())
                if(loader instanceof java.net.URLClassLoader)for(java.net.URL u:((java.net.URLClassLoader)loader).getURLs())if("file".equals(u.getProtocol()))entries.add(Paths.get(u.toURI()).toString());
            Files.write(Paths.get(System.getProperty("a1.classpath.output")),String.join(java.io.File.pathSeparator,entries).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String url=System.getProperty("a1.mysql.url", "");
        if(!url.startsWith("jdbc:mysql://127.0.0.1:33357/a1_isolated?"))throw new IllegalStateException("Explicit isolated A1 MySQL URL is required");
        DriverManagerDataSource ds=new DriverManagerDataSource(url,"root","");ds.setDriverClassName("com.mysql.cj.jdbc.Driver");db=new JdbcTemplate(ds);
        String dataDir=db.queryForObject("select @@datadir",String.class).replace('\\','/');
        if(!dataDir.equalsIgnoreCase("D:/Code/agentReport/a1-output-20261014/mysql-data/"))throw new IllegalStateException("Refusing non-isolated database directory");
        Path base=Paths.get(System.getProperty("a1.module",System.getProperty("basedir",".")));
        String ddl=new String(Files.readAllBytes(base.resolve("sql/ai_workbook_20260824.sql")),java.nio.charset.StandardCharsets.UTF_8);
        ddl=ddl.substring(ddl.indexOf("CREATE TABLE ai_workbook")).replace("CREATE TABLE ai_workbook", "CREATE TABLE IF NOT EXISTS ai_workbook");
        if(Boolean.getBoolean("a1.schema.prepared")){
            for(String table:Arrays.asList("ai_workbook","ai_workbook_branch","ai_workbook_working_state","ai_agent_run","ai_agent_lease","ai_agent_plan","ai_agent_step","ai_agent_tool_call","ai_agent_event","ai_analysis_artifact"))
                if(db.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name=?",Integer.class,table)!=1)throw new IllegalStateException("Prepared isolated schema missing "+table);
        }else{
            executeSql(ddl);executeSql(new String(Files.readAllBytes(base.resolve("sql/ai_workbook_git_20260914.sql")),java.nio.charset.StandardCharsets.UTF_8));
            executeSql(new String(Files.readAllBytes(base.resolve("sql/ai_agent_20261013.sql")),java.nio.charset.StandardCharsets.UTF_8));
        }
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/AiWorkbookMapper.xml"),new ClassPathResource("mapper/WorkbookVersionMapper.xml"));
        SqlSessionTemplate session=new SqlSessionTemplate(factory.getObject());workbooks=session.getMapper(AiWorkbookMapper.class);versions=session.getMapper(WorkbookVersionMapper.class);
        guard=new AgentWriteGuard(db);service=new WorkbookVersionServiceImpl();inject(service,"workbookMapper",workbooks);inject(service,"versionMapper",versions);inject(service,"agentWriteGuard",guard);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        AgentStore store=new AgentStore(db);runs=new AgentRunService(store,guard,new AgentTools(store),workbooks,versions,null,manager);
    }
    private void executeSql(String ddl){for(String sql:ddl.replaceAll("(?m)^--.*$","").split(";"))if(!sql.trim().isEmpty())db.execute(sql);}
    public static void inject(Object object,String field,Object value)throws Exception{Field f=object.getClass().getDeclaredField(field);f.setAccessible(true);f.set(object,value);}
    public void identity(){SecurityContextHolder.setUserId("1");SecurityContextHolder.setUserName("a1-test");}
    public long seed(String snapshot) {
        db.update("insert into ai_workbook(name,content,content_hash,user_id,create_time) values(?,?,?,?,now())","a1-"+UUID.randomUUID(),snapshot,com.ruoyi.ai.util.ContentHashUtils.sha256(snapshot),1);
        long id=db.queryForObject("select max(workbook_id) from ai_workbook",Long.class);
        db.update("insert into ai_workbook_branch(workbook_id,branch_name,owner_user_id,created_time,update_time) values(?,'main',1,now(3),now(3))",id);
        long branch=db.queryForObject("select branch_id from ai_workbook_branch where workbook_id=?",Long.class,id);
        db.update("update ai_workbook set current_branch_id=? where workbook_id=?",branch,id);return id;
    }
    public WorkingStateRequest request(long id,long version,String loaded,String content) {
        WorkingStateRequest r=new WorkingStateRequest();r.setBranchId(db.queryForObject("select current_branch_id from ai_workbook where workbook_id=?",Long.class,id));
        r.setBaseVersion(0L);r.setExpectedDraftVersion(version);r.setLoadedRevision(loaded);r.setContent(content);r.setPlannedActions(Collections.emptyList());r.setExecutedChanges(Collections.emptyList());return r;
    }
    public R<Map<String,Object>> save(long id,WorkingStateRequest request){identity();return tx.execute(s->service.saveWorkingState(id,request));}
    public Map<String,Object> status(long id){identity();return tx.execute(s->service.status(id)).getData();}
}
