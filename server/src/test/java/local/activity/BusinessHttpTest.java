package local.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessHttpTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static ConfigurableApplicationContext context;
    static String base;
    static JdbcTemplate jdbc;
    static int serial;

    @BeforeAll static void start() {
        context = new SpringApplicationBuilder(ActivityApplication.class).run(
            "--server.port=0", "--spring.datasource.url=jdbc:h2:mem:business;DB_CLOSE_DELAY=-1;WRITE_DELAY=0");
        base = "http://127.0.0.1:" + ((ServletWebServerApplicationContext)context).getWebServer().getPort();
        jdbc = context.getBean(JdbcTemplate.class);
    }
    @AfterAll static void stop() { if(context != null) context.close(); }

    static class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        final String server;
        String csrf;
        Browser() throws Exception { this(base); }
        Browser(String server) throws Exception { this.server=server; session(); }
        JsonNode session() throws Exception { var r=send("GET","/api/session",null,true); assertEquals(200,r.statusCode(),r.body()); var n=JSON.readTree(r.body()); csrf=n.path("csrfToken").asText(); assertFalse(csrf.isBlank()); return n; }
        HttpResponse<String> send(String method,String path,Object body,boolean token) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create(server+path)).timeout(Duration.ofSeconds(20));
            if(token && csrf!=null) builder.header("X-CSRF-Token",csrf);
            if(body!=null) builder.header("Content-Type","application/json");
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
        JsonNode ok(String method,String path,Object body) throws Exception { var r=send(method,path,body,true); assertTrue(r.statusCode()>=200 && r.statusCode()<300,r.statusCode()+" "+r.body()); return r.body().isBlank()?JSON.nullNode():JSON.readTree(r.body()); }
        void status(int expected,String method,String path,Object body) throws Exception { var r=send(method,path,body,true); assertEquals(expected,r.statusCode(),r.body()); assertFalse(JSON.readTree(r.body()).path("code").asText().isBlank()); }
        String register() throws Exception { String user="person_"+(++serial); ok("POST","/api/register",Map.of("username",user,"password","Working-pass-2026!")); session(); return user; }
    }
    static JsonNode group(Browser b) throws Exception { return b.ok("POST","/api/groups",Map.of("name","周末一起去","eventDate","2026-10-24")); }
    static Map<String,Object> candidate(String title) { var m=new LinkedHashMap<String,Object>(); m.put("title",title); m.put("address","昆明市五华区"); m.put("timeWindow","周六 14:00—16:00"); m.put("sourceUrl","https://example.org/official-rules"); m.put("verifiedAt","2026-10-17"); m.put("openingText","以官方当日公告为准"); m.put("openingStatus","unknown"); m.put("feeText",""); m.put("feeStatus","unknown"); m.put("reservationText","尚待确认"); m.put("reservationStatus","unknown"); m.put("internalNote","PRIVATE_MARKER_不公开"); return m; }
    static JsonNode add(Browser b,String id,String title) throws Exception { return b.ok("POST","/api/groups/"+id+"/candidates",candidate(title)); }
    static JsonNode invite(Browser owner,String id,Browser guest) throws Exception { var i=owner.ok("POST","/api/groups/"+id+"/invitations",Map.of("expiresInMinutes",60)); return guest.ok("POST","/api/invitations/claim",Map.of("token",i.path("token").asText())); }
    static Map<String,Object> decision(JsonNode g,String main,String backup) { return Map.of("mainId",main,"backupId",backup,"meetingTime","2026-10-24 13:45","meetingPlace","东门集合点","publicNote","天气变化时由组织者通知调整","expectedVersion",g.path("version").asInt()); }
    static JsonNode get(Browser b,String id) throws Exception { return b.ok("GET","/api/groups/"+id,null); }

    @Test void normalRegistrationHashesPasswordsAndRotatesSessionCsrf() throws Exception {
        Browser b=new Browser(); var before=b.csrf; String user=b.register(); assertEquals(user,b.session().path("user").path("username").asText()); assertNotEquals(before,b.csrf);
        var hashes=jdbc.queryForList("select password_hash from act_users where username=?",String.class,user); assertEquals(1,hashes.size()); assertFalse(hashes.getFirst().contains("Working-pass"));
        var stale=b.csrf; b.ok("POST","/api/logout",null); b.session(); assertTrue(b.session().path("user").isNull());
        b.status(401,"POST","/api/login",Map.of("username",user,"password","wrong-password"));
        b.ok("POST","/api/login",Map.of("username",user,"password","Working-pass-2026!")); b.session(); assertNotEquals(stale,b.csrf);
        b.status(409,"POST","/api/register",Map.of("username",user,"password","Working-pass-2026!"));
        var response=b.send("POST","/api/groups",Map.of("name","禁止无CSRF","eventDate","2026-10-24"),false); assertEquals(403,response.statusCode());
    }

    @Test void threeNewAccountsJoinOnceAndOutsideAccountCannotReadOrWrite() throws Exception {
        Browser a=new Browser(),b=new Browser(),c=new Browser(),d=new Browser(); a.register(); b.register(); c.register(); d.register(); String id=group(a).path("id").asText();
        var token=a.ok("POST","/api/groups/"+id+"/invitations",Map.of("expiresInMinutes",60)); String plain=token.path("token").asText(); assertTrue(plain.length()>=22);
        assertNotEquals(plain,jdbc.queryForObject("select token_hash from invitations where id=?",String.class,token.path("id").asText()));
        b.ok("POST","/api/invitations/claim",Map.of("token",plain)); c.status(409,"POST","/api/invitations/claim",Map.of("token",plain)); invite(a,id,c);
        assertEquals(3,get(c,id).path("members").size()); assertEquals("member",get(c,id).path("role").asText());
        d.status(403,"GET","/api/groups/"+id,null); d.status(403,"POST","/api/groups/"+id+"/candidates",candidate("外部成员不能添加")); assertEquals(0,d.ok("GET","/api/groups",null).size());
    }

    @Test void expiredRevokedAndUsedInvitationsDoNotCreateMembership() throws Exception {
        Browser a=new Browser(),b=new Browser(); a.register(); b.register(); String id=group(a).path("id").asText();
        var old=a.ok("POST","/api/groups/"+id+"/invitations",Map.of("expiresInMinutes",1)); jdbc.update("update invitations set expires_at=? where id=?",Instant.now().minusSeconds(5).toString(),old.path("id").asText());
        b.status(410,"POST","/api/invitations/claim",Map.of("token",old.path("token").asText()));
        var revoked=a.ok("POST","/api/groups/"+id+"/invitations",Map.of()); a.ok("DELETE","/api/groups/"+id+"/invitations/"+revoked.path("id").asText(),null);
        b.status(410,"POST","/api/invitations/claim",Map.of("token",revoked.path("token").asText())); assertEquals(1,get(a,id).path("members").size());
        a.status(400,"POST","/api/groups/"+id+"/invitations",Map.of("expiresInMinutes",10081)); b.status(400,"POST","/api/invitations/claim",Map.of("token","tiny"));
    }

    @Test void unknownRulesStayUnknownAndVerifiedNeedsSourceDateAndText() throws Exception {
        Browser a=new Browser(); a.register(); String id=group(a).path("id").asText(); var unknown=add(a,id,"待核实场馆"); assertEquals("unknown",unknown.path("feeStatus").asText()); assertEquals("",unknown.path("feeText").asText());
        var invalid=candidate("不完整依据"); invalid.put("openingStatus","verified"); invalid.put("sourceUrl",""); a.status(400,"POST","/api/groups/"+id+"/candidates",invalid);
        invalid.put("sourceUrl","https://example.org"); invalid.put("verifiedAt",""); a.status(400,"POST","/api/groups/"+id+"/candidates",invalid);
        invalid.put("verifiedAt","2026-10-17"); invalid.put("openingText",""); a.status(400,"POST","/api/groups/"+id+"/candidates",invalid);
        for(String url:List.of("javascript:alert(1)","file:///etc/passwd","https://user:pass@example.org/x","http://example.org/\nheader")) { var x=candidate("坏来源"); x.put("sourceUrl",url); a.status(400,"POST","/api/groups/"+id+"/candidates",x); }
    }

    @Test void candidateWritesAreScopedAuthorRestrictedAndOptimistic() throws Exception {
        Browser a=new Browser(),b=new Browser(),c=new Browser(); a.register(); b.register(); c.register(); String id=group(a).path("id").asText(); invite(a,id,b); invite(a,id,c); var x=add(b,id,"参与者方案"); String cid=x.path("id").asText(); var update=candidate("修改后方案"); update.put("expectedVersion",x.path("version").asInt());
        c.status(403,"PUT","/api/groups/"+id+"/candidates/"+cid,update); b.ok("PUT","/api/groups/"+id+"/candidates/"+cid,update); b.status(409,"PUT","/api/groups/"+id+"/candidates/"+cid,update);
        String another=group(a).path("id").asText(); a.status(404,"PUT","/api/groups/"+another+"/candidates/"+cid,update);
    }

    @Test void concurrentPreferenceRetriesRemainOneAndCanChangeOrWithdraw() throws Exception {
        Browser a=new Browser(),b=new Browser(); a.register(); String who=b.register(); String id=group(a).path("id").asText(); invite(a,id,b); String cid=add(a,id,"候选").path("id").asText(); String path="/api/groups/"+id+"/candidates/"+cid+"/preference";
        try(var pool=Executors.newFixedThreadPool(6)) { var tasks=new ArrayList<Callable<Integer>>(); for(int i=0;i<12;i++) tasks.add(()->b.send("PUT",path,Map.of("choice","want"),true).statusCode()); for(var f:pool.invokeAll(tasks)) assertEquals(200,f.get()); }
        assertEquals(1,get(a,id).path("preferences").size()); assertEquals(1,jdbc.queryForObject("select count(*) from preferences where group_id=? and candidate_id=? and username=?",Integer.class,id,cid,who));
        b.ok("PUT",path,Map.of("choice","avoid")); assertEquals("avoid",get(a,id).path("preferences").get(0).path("choice").asText());
        b.status(400,"PUT",path,Map.of("choice","want","username","someone-else")); b.ok("DELETE",path,null); b.ok("DELETE",path,null); assertEquals(0,get(a,id).path("preferences").size());
    }

    @Test void onlyOwnerCanDecideDistinctLocalCandidatesAtLatestGroupVersion() throws Exception {
        Browser a=new Browser(),b=new Browser(); a.register(); b.register(); String id=group(a).path("id").asText(); invite(a,id,b); String main=add(a,id,"主方案").path("id").asText(), backup=add(b,id,"备用").path("id").asText();
        var state=get(a,id); b.status(403,"PUT","/api/groups/"+id+"/decision",decision(state,main,backup)); a.status(400,"PUT","/api/groups/"+id+"/decision",decision(state,main,main));
        String otherId=group(a).path("id").asText(),foreign=add(a,otherId,"其他活动").path("id").asText(); a.status(400,"PUT","/api/groups/"+id+"/decision",decision(state,main,foreign));
        b.ok("PUT","/api/groups/"+id+"/candidates/"+main+"/preference",Map.of("choice","want")); a.status(409,"PUT","/api/groups/"+id+"/decision",decision(state,main,backup));
        var decided=a.ok("PUT","/api/groups/"+id+"/decision",decision(get(a,id),main,backup)); assertEquals(main,decided.path("decision").path("mainId").asText()); assertEquals(backup,decided.path("decision").path("backupId").asText());
    }

    @Test void anonymousPublicationIsWhitelistFrozenVersionedAndReallyRevoked() throws Exception {
        Browser a=new Browser(),b=new Browser(),anonymous=new Browser(); a.register(); b.register(); String id=group(a).path("id").asText(); invite(a,id,b); String main=add(a,id,"公开主方案").path("id").asText(),backup=add(a,id,"公开备用").path("id").asText(); add(a,id,"UNSELECTED_MARKER_不可公开");
        a.status(409,"POST","/api/groups/"+id+"/publications",Map.of()); a.ok("PUT","/api/groups/"+id+"/decision",decision(get(a,id),main,backup)); b.status(403,"POST","/api/groups/"+id+"/publications",Map.of());
        var p=a.ok("POST","/api/groups/"+id+"/publications",Map.of()); String pid=p.path("id").asText(); var original=anonymous.ok("GET","/api/publications/"+pid,null); String frozen=original.path("content").toString();
        assertTrue(original.path("isLatest").asBoolean()); assertFalse(frozen.contains("PRIVATE_MARKER")); assertFalse(frozen.contains("UNSELECTED_MARKER")); assertFalse(frozen.contains("createdBy")); assertFalse(frozen.contains("username")); assertFalse(frozen.contains("preferences")); assertFalse(frozen.contains("internalNote")); assertFalse(original.path("content").path("main").has("id"));
        var changed=candidate("主方案已改但v1不变"); changed.put("expectedVersion",1); a.ok("PUT","/api/groups/"+id+"/candidates/"+main,changed);
        assertEquals(frozen,anonymous.ok("GET","/api/publications/"+pid,null).path("content").toString()); var p2=a.ok("POST","/api/groups/"+id+"/publications",Map.of()); assertEquals(2,p2.path("version").asInt());
        assertFalse(anonymous.ok("GET","/api/publications/"+pid,null).path("isLatest").asBoolean()); assertTrue(anonymous.ok("GET","/api/publications/"+p2.path("id").asText(),null).path("isLatest").asBoolean());
        b.status(403,"DELETE","/api/groups/"+id+"/publications/"+pid,null); a.ok("DELETE","/api/groups/"+id+"/publications/"+pid,null); var revoked=anonymous.send("GET","/api/publications/"+pid,null,true); assertEquals(410,revoked.statusCode()); assertFalse(revoked.body().contains("公开主方案")); assertFalse(revoked.body().contains("content")); assertEquals(200,anonymous.send("GET","/api/publications/"+p2.path("id").asText(),null,true).statusCode());
    }

    @Test void malformedInputsCannotBecomeTruncatedOrCoercedBusinessData() throws Exception {
        Browser a=new Browser(); a.status(400,"POST","/api/register",Map.of("username","x","password","1234567")); a.status(400,"POST","/api/register",Map.of("username","valid_user","password","a".repeat(129))); a.register();
        a.status(400,"POST","/api/groups",Map.of("name"," ","eventDate","2026-10-24")); a.status(400,"POST","/api/groups",Map.of("name","x".repeat(81),"eventDate","2026-10-24")); a.status(400,"POST","/api/groups",Map.of("name","日期错","eventDate","2026-02-30"));
        String id=group(a).path("id").asText(); var x=candidate("过长"); x.put("internalNote","x".repeat(2001)); a.status(400,"POST","/api/groups/"+id+"/candidates",x); x=candidate("坏枚举"); x.put("feeStatus","free"); a.status(400,"POST","/api/groups/"+id+"/candidates",x);
        x=candidate("额外身份"); x.put("createdBy","victim"); a.status(400,"POST","/api/groups/"+id+"/candidates",x);
        x=candidate("非法类型"); x.put("address",Map.of("nested",true)); a.status(400,"POST","/api/groups/"+id+"/candidates",x);
    }

    @Test void untrustedOriginAndMissingAuthenticationCannotReadOrMutatePrivateData() throws Exception {
        Browser anonymous=new Browser(); anonymous.status(401,"GET","/api/groups",null); var req=HttpRequest.newBuilder(URI.create(base+"/api/session")).header("Origin","https://attacker.example").GET().build(); assertEquals(403,HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString()).statusCode());
        Browser a=new Browser(); a.register(); var req2=HttpRequest.newBuilder(URI.create(base+"/api/groups")).header("Content-Type","application/json").header("Origin","http://localhost:6666").header("X-CSRF-Token",a.csrf).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"坏来源\",\"eventDate\":\"2026-10-24\"}")).build(); assertEquals(403,a.http.send(req2,HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void numericFieldsRejectFractionsAndTextInsteadOfSilentlyChangingVersions() throws Exception {
        Browser a=new Browser(); a.register(); String id=group(a).path("id").asText();
        a.status(400,"POST","/api/groups/"+id+"/invitations",Map.of("expiresInMinutes",1.5));
        a.status(400,"POST","/api/groups/"+id+"/invitations",Map.of("expiresInMinutes","60"));
        String cid=add(a,id,"版本不能截断").path("id").asText(); var update=candidate("不应成功"); update.put("expectedVersion",1.5); a.status(400,"PUT","/api/groups/"+id+"/candidates/"+cid,update);
        update.put("expectedVersion","1"); a.status(400,"PUT","/api/groups/"+id+"/candidates/"+cid,update); assertEquals(1,get(a,id).path("candidates").get(0).path("version").asInt());
    }

    @Test void emptyPublishContractCannotIgnoreAttackerSuppliedIdentityOrContent() throws Exception {
        Browser a=new Browser(); a.register(); String id=group(a).path("id").asText(); String main=add(a,id,"主").path("id").asText(),backup=add(a,id,"备").path("id").asText(); a.ok("PUT","/api/groups/"+id+"/decision",decision(get(a,id),main,backup));
        a.status(400,"POST","/api/groups/"+id+"/publications",Map.of("content",Map.of("internalNote","不得采用"))); assertEquals(0,get(a,id).path("publications").size());
    }

    @Test void allCandidateLengthsAndDecisionLengthsHaveAcceptedBoundariesAndRejectedOverflow() throws Exception {
        Browser a=new Browser(); a.register(); String id=a.ok("POST","/api/groups",Map.of("name","组".repeat(80),"eventDate","2026-10-24")).path("id").asText();
        var lengths=Map.of("title",120,"address",300,"timeWindow",120,"openingText",500,"feeText",500,"reservationText",500,"internalNote",2000);
        for(var item:lengths.entrySet()) {
            var valid=candidate("长度边界"); valid.put(item.getKey(),"字".repeat(item.getValue())); a.ok("POST","/api/groups/"+id+"/candidates",valid);
            valid.put(item.getKey(),"字".repeat(item.getValue()+1)); a.status(400,"POST","/api/groups/"+id+"/candidates",valid);
        }
        String prefix="https://example.org/"; var url=candidate("URL边界"); url.put("sourceUrl",prefix+"a".repeat(2048-prefix.length())); a.ok("POST","/api/groups/"+id+"/candidates",url); url.put("sourceUrl",prefix+"a".repeat(2049-prefix.length())); a.status(400,"POST","/api/groups/"+id+"/candidates",url);
        String main=add(a,id,"主").path("id").asText(),backup=add(a,id,"备").path("id").asText();
        for(var entry:Map.of("meetingTime",120,"meetingPlace",300,"publicNote",1000).entrySet()) {
            var valid=new HashMap<String,Object>(decision(get(a,id),main,backup)); valid.put(entry.getKey(),"字".repeat(entry.getValue())); a.ok("PUT","/api/groups/"+id+"/decision",valid);
            valid=new HashMap<>(decision(get(a,id),main,backup)); valid.put(entry.getKey(),"字".repeat(entry.getValue()+1)); a.status(400,"PUT","/api/groups/"+id+"/decision",valid);
        }
    }

    @Test void oldPreLoginSessionCannotUseItsCsrfAfterAuthentication() throws Exception {
        Browser a=new Browser(); String before=a.csrf; a.register(); String current=a.csrf; a.csrf=before; a.status(403,"POST","/api/groups",Map.of("name","旧令牌不可使用","eventDate","2026-10-24")); a.csrf=current; assertNotNull(group(a));
        var response=a.send("GET","/api/no-such-endpoint",null,true); assertEquals(404,response.statusCode()); assertEquals("not_found",JSON.readTree(response.body()).path("code").asText());
    }
}
