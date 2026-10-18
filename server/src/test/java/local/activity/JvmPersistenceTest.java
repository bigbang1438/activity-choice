package local.activity;

import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static local.activity.BusinessHttpTest.*;

class JvmPersistenceTest {
    @Test void twoIndependentJvmsKeepUsersVotesDecisionFrozenContentAndRevocation() throws Exception {
        Path data=Files.createTempDirectory(Path.of("target"),"persistence-").toAbsolutePath();
        String groupId,mainId,user,pid,pid2,expiredInvitation;
        String frozen;
        try(Running first=start(data,1)) {
            Browser owner=new Browser(first.url),guest=new Browser(first.url); user=owner.register(); guest.register(); groupId=group(owner).path("id").asText(); invite(owner,groupId,guest);
            mainId=add(owner,groupId,"重开后主方案").path("id").asText(); String backupId=add(guest,groupId,"重开后备用").path("id").asText();
            guest.ok("PUT","/api/groups/"+groupId+"/candidates/"+mainId+"/preference",Map.of("choice","want")); owner.ok("PUT","/api/groups/"+groupId+"/decision",decision(get(owner,groupId),mainId,backupId));
            var p=owner.ok("POST","/api/groups/"+groupId+"/publications",Map.of()); pid=p.path("id").asText(); frozen=owner.ok("GET","/api/publications/"+pid,null).path("content").toString();
            var changed=candidate("草稿新标题不应改旧发布"); changed.put("expectedVersion",1); owner.ok("PUT","/api/groups/"+groupId+"/candidates/"+mainId,changed);
            pid2=owner.ok("POST","/api/groups/"+groupId+"/publications",Map.of()).path("id").asText(); owner.ok("DELETE","/api/groups/"+groupId+"/publications/"+pid2,null);
            var i=owner.ok("POST","/api/groups/"+groupId+"/invitations",Map.of()); expiredInvitation=i.path("token").asText(); owner.ok("DELETE","/api/groups/"+groupId+"/invitations/"+i.path("id").asText(),null);
        }
        try(Running second=start(data,2)) {
            Browser owner=new Browser(second.url),anonymous=new Browser(second.url); owner.ok("POST","/api/login",Map.of("username",user,"password","Working-pass-2026!")); owner.session();
            var state=get(owner,groupId); assertEquals(2,state.path("members").size()); assertEquals(2,state.path("candidates").size()); assertEquals(1,state.path("preferences").size()); assertEquals(mainId,state.path("decision").path("mainId").asText());
            assertTrue(state.path("candidates").findValuesAsText("title").contains("草稿新标题不应改旧发布"));
            var publication=anonymous.ok("GET","/api/publications/"+pid,null); assertEquals(frozen,publication.path("content").toString()); assertFalse(publication.path("isLatest").asBoolean()); anonymous.status(410,"GET","/api/publications/"+pid2,null);
            Browser newcomer=new Browser(second.url); newcomer.register(); newcomer.status(410,"POST","/api/invitations/claim",Map.of("token",expiredInvitation));
        }
    }
    private Running start(Path data,int run) throws Exception {
        int port; try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path log=data.resolve("jvm-"+run+".log");
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",cp,"local.activity.ActivityApplication","--server.port="+port,"--spring.datasource.url=jdbc:h2:file:"+data.resolve("activity").toString().replace('\\','/')+";WRITE_DELAY=0;DB_CLOSE_ON_EXIT=FALSE");
        Process process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        String url="http://127.0.0.1:"+port; long until=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        try {
            HttpClient http=HttpClient.newHttpClient();
            while(System.nanoTime()<until) {
                if(!process.isAlive()) fail("Child JVM exited: "+Files.readString(log));
                try { var response=http.send(HttpRequest.newBuilder(URI.create(url+"/api/session")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.ofString()); if(response.statusCode()==200)return new Running(process,url); } catch(java.io.IOException e) { /* wait for the owned child server */ }
                Thread.sleep(100);
            }
            fail("Child server startup timeout: "+Files.readString(log)); return null;
        } catch(Throwable failure) { process.destroy(); process.waitFor(10,TimeUnit.SECONDS); if(process.isAlive())process.destroyForcibly(); throw failure; }
    }
    private record Running(Process process,String url) implements AutoCloseable {
        @Override public void close() throws Exception { process.destroy(); if(!process.waitFor(15,TimeUnit.SECONDS)){process.destroyForcibly(); assertTrue(process.waitFor(5,TimeUnit.SECONDS));} }
    }
}
