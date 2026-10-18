package local.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
class ActivityService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String dummyHash=Passwords.hash(UUID.randomUUID().toString());
    ActivityService(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }

    record Credentials(String username,String password) {}
    record GroupInput(String name,String eventDate) {}
    record InviteInput(Integer expiresInMinutes) {}
    record ClaimInput(String token) {}
    record PreferenceInput(String choice) {}
    record CandidateInput(String title,String address,String timeWindow,String sourceUrl,String verifiedAt,
                          String openingText,String openingStatus,String feeText,String feeStatus,
                          String reservationText,String reservationStatus,String internalNote,Long expectedVersion) {}
    record DecisionInput(String mainId,String backupId,String meetingTime,String meetingPlace,String publicNote,Long expectedVersion) {}

    String validateUsername(String username) {
        if(username==null || !username.matches("[A-Za-z0-9_]{3,32}")) throw ApiError.invalid("用户名须为3—32位字母、数字或下划线");
        return username.toLowerCase(Locale.ROOT);
    }
    private String password(String password) {
        if(password==null || password.length()<8 || password.length()>128 || password.indexOf('\0')>=0) throw ApiError.invalid("密码须为8—128个字符");
        return password;
    }
    @Transactional
    public String register(Credentials input) {
        String username=validateUsername(input.username()); String password=password(input.password());
        if(count("select count(*) from act_users where username=?",username)>0) throw ApiError.conflict("用户名已被使用");
        try { db.update("insert into act_users(username,password_hash) values (?,?)",username,Passwords.hash(password)); }
        catch(DuplicateKeyException e) { throw ApiError.conflict("用户名已被使用"); }
        return username;
    }
    public String authenticate(Credentials input) {
        String username=validateUsername(input.username()), password=password(input.password());
        List<String> found=db.queryForList("select password_hash from act_users where username=?",String.class,username);
        if(!Passwords.matches(password,found.isEmpty()?dummyHash:found.getFirst()) || found.isEmpty()) throw new ApiError(401,"unauthenticated","用户名或密码错误");
        return username;
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> groups(String username) {
        return db.query("select g.id,g.name,g.event_date,g.version,m.role from activity_groups g join members m on g.id=m.group_id where m.username=? order by g.id",
            (r,n)->map("id",r.getString("id"),"name",r.getString("name"),"eventDate",r.getString("event_date"),"version",r.getLong("version"),"role",r.getString("role")),username);
    }
    @Transactional
    public Map<String,Object> createGroup(String username,GroupInput input) {
        String name=text(input.name(),"活动名称",1,80), date=date(input.eventDate(),"活动日期",true),id=id();
        db.update("insert into activity_groups(id,name,event_date,owner_name,version) values (?,?,?,?,1)",id,name,date,username);
        db.update("insert into members(group_id,username,role) values (?,?,'owner')",id,username);
        return view(id,username);
    }
    @Transactional
    public Map<String,Object> getGroup(String id,String username) { member(lock(id),username); return view(id,username); }

    @Transactional
    public Map<String,Object> createInvitation(String id,String username,InviteInput input) {
        owner(lock(id),username);
        int minutes=input.expiresInMinutes()==null?60:input.expiresInMinutes();
        if(minutes<1 || minutes>10080) throw ApiError.invalid("邀请有效期为1分钟至7天");
        String token=LocalSecurity.randomToken(),invitation=id(),expires=Instant.now().plusSeconds(minutes*60L).toString();
        db.update("insert into invitations(id,group_id,token_hash,expires_at,revoked) values (?,?,?,?,false)",invitation,id,sha256(token),expires);
        return map("id",invitation,"token",token,"expiresAt",expires);
    }
    @Transactional
    public void revokeInvitation(String id,String invitationId,String username) {
        owner(lock(id),username); uuid(invitationId);
        if(db.update("update invitations set revoked=true where id=? and group_id=?",invitationId,id)!=1) throw ApiError.missing();
    }
    @Transactional
    public Map<String,Object> claim(String username,ClaimInput input) {
        String token=input.token();
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw ApiError.invalid("邀请令牌格式无效");
        List<Map<String,Object>> found=db.queryForList("select * from invitations where token_hash=?",sha256(token));
        if(found.isEmpty()) throw ApiError.missing();
        String groupId=(String)found.getFirst().get("group_id");
        lock(groupId);
        // The group lock serializes claims and revocation; always re-read after acquiring it.
        Map<String,Object> invitation=db.queryForMap("select * from invitations where token_hash=? for update",sha256(token));
        if(Boolean.TRUE.equals(invitation.get("revoked")) || !Instant.parse((String)invitation.get("expires_at")).isAfter(Instant.now())) throw new ApiError(410,"gone","邀请已失效");
        if(invitation.get("claimed_by")!=null) throw ApiError.conflict("邀请已被领取");
        if(count("select count(*) from members where group_id=? and username=?",groupId,username)>0) throw ApiError.conflict("你已加入此活动");
        db.update("insert into members(group_id,username,role) values (?,?,'member')",groupId,username);
        db.update("update invitations set claimed_by=? where id=?",username,invitation.get("id"));
        bump(groupId); return view(groupId,username);
    }
    @Transactional
    public Map<String,Object> createCandidate(String id,String username,CandidateInput input) {
        member(lock(id),username);
        if(input.expectedVersion()!=null) throw ApiError.invalid("创建候选无需版本号");
        Map<String,Object> fields=candidateFields(input); String cid=id();
        db.update("insert into candidates(id,group_id,created_by,version,payload) values (?,?,?,1,?)",cid,id,username,encode(fields));
        bump(id); return candidate(candidateRow(id,cid));
    }
    @Transactional
    public Map<String,Object> updateCandidate(String id,String candidateId,String username,CandidateInput input) {
        Map<String,Object> group=lock(id); member(group,username);
        Map<String,Object> row=candidateRow(id,candidateId);
        if(!username.equals(row.get("created_by")) && !username.equals(group.get("owner_name"))) throw ApiError.forbidden();
        long expected=expected(input.expectedVersion());
        if(((Number)row.get("version")).longValue()!=expected) throw ApiError.conflict("候选已变化，请刷新后再修改");
        Map<String,Object> fields=candidateFields(input);
        db.update("update candidates set payload=?,version=version+1 where id=? and group_id=? and version=?",encode(fields),candidateId,id,expected);
        bump(id); return candidate(candidateRow(id,candidateId));
    }
    @Transactional
    public Map<String,Object> preference(String id,String cid,String username,PreferenceInput input) {
        member(lock(id),username); candidateRow(id,cid);
        String choice=text(input.choice(),"偏好",1,8);
        if(!Set.of("want","okay","avoid").contains(choice)) throw ApiError.invalid("偏好仅支持想去、可接受或不想去");
        List<String> current=db.queryForList("select choice from preferences where group_id=? and candidate_id=? and username=?",String.class,id,cid,username);
        if(current.isEmpty()) { db.update("insert into preferences(group_id,candidate_id,username,choice) values (?,?,?,?)",id,cid,username,choice); bump(id); }
        else if(!current.getFirst().equals(choice)) { db.update("update preferences set choice=? where group_id=? and candidate_id=? and username=?",choice,id,cid,username); bump(id); }
        return view(id,username);
    }
    @Transactional
    public Map<String,Object> withdrawPreference(String id,String cid,String username) {
        member(lock(id),username); candidateRow(id,cid);
        if(db.update("delete from preferences where group_id=? and candidate_id=? and username=?",id,cid,username)>0) bump(id);
        return view(id,username);
    }
    @Transactional
    public Map<String,Object> decide(String id,String username,DecisionInput input) {
        Map<String,Object> group=lock(id); owner(group,username);
        long expected=expected(input.expectedVersion());
        if(expected!=((Number)group.get("version")).longValue()) throw ApiError.conflict("活动方案或偏好已变化，请刷新后再定案");
        uuid(input.mainId()); uuid(input.backupId());
        if(input.mainId().equals(input.backupId())) throw ApiError.invalid("主方案和备用方案不能相同");
        if(count("select count(*) from candidates where group_id=? and id in (?,?)",id,input.mainId(),input.backupId())!=2) throw ApiError.invalid("主备方案必须属于当前活动");
        Map<String,Object> d=map("mainId",input.mainId(),"backupId",input.backupId(),"meetingTime",text(input.meetingTime(),"集合时间",1,120),"meetingPlace",text(input.meetingPlace(),"集合地点",1,300),"publicNote",text(input.publicNote(),"公开备注",0,1000));
        db.update("merge into decisions(group_id,main_id,backup_id,payload) key(group_id) values (?,?,?,?)",id,input.mainId(),input.backupId(),encode(d));
        bump(id); return view(id,username);
    }
    @Transactional
    public Map<String,Object> publish(String id,String username) {
        Map<String,Object> group=lock(id); owner(group,username);
        List<String> decisions=db.queryForList("select payload from decisions where group_id=?",String.class,id);
        if(decisions.isEmpty()) throw ApiError.conflict("请先确认主备方案与集合信息");
        Map<String,Object> d=decode(decisions.getFirst());
        Map<String,Object> content=map("groupName",group.get("name"),"eventDate",group.get("event_date"),
            "main",publicCandidate(candidate(candidateRow(id,(String)d.get("mainId")))),
            "backup",publicCandidate(candidate(candidateRow(id,(String)d.get("backupId")))),
            "meetingTime",d.get("meetingTime"),"meetingPlace",d.get("meetingPlace"),"publicNote",d.get("publicNote"));
        long version=latest(id)+1; String pid=id(),published=Instant.now().toString();
        db.update("insert into publications(id,group_id,version,published_at,content,revoked) values (?,?,?,?,?,false)",pid,id,version,published,encode(content));
        return map("id",pid,"url","/share/"+pid,"version",version,"publishedAt",published);
    }
    @Transactional
    public void revokePublication(String id,String pid,String username) {
        owner(lock(id),username); uuid(pid);
        if(db.update("update publications set revoked=true where id=? and group_id=?",pid,id)!=1) throw ApiError.missing();
    }
    @Transactional(readOnly=true)
    public Map<String,Object> publication(String pid) {
        uuid(pid); List<Map<String,Object>> found=db.queryForList("select * from publications where id=?",pid);
        if(found.isEmpty()) throw ApiError.missing(); Map<String,Object> row=found.getFirst();
        if(Boolean.TRUE.equals(row.get("revoked"))) throw new ApiError(410,"gone","此分享已由组织者撤销");
        return map("id",pid,"version",row.get("version"),"publishedAt",row.get("published_at"),"isLatest",((Number)row.get("version")).longValue()==latest((String)row.get("group_id")),"content",decode(string(row.get("content"))));
    }

    private Map<String,Object> lock(String id) {
        uuid(id); List<Map<String,Object>> rows=db.queryForList("select * from activity_groups where id=? for update",id);
        if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst();
    }
    private void member(Map<String,Object> group,String username) {
        if(count("select count(*) from members where group_id=? and username=?",group.get("id"),username)==0) throw ApiError.forbidden();
    }
    private void owner(Map<String,Object> group,String username) { if(!username.equals(group.get("owner_name"))) throw ApiError.forbidden(); }
    private void bump(String id) { db.update("update activity_groups set version=version+1 where id=?",id); }
    private long latest(String id) { Long value=db.queryForObject("select coalesce(max(version),0) from publications where group_id=?",Long.class,id); return value==null?0:value; }
    private int count(String sql,Object... args) { Integer result=db.queryForObject(sql,Integer.class,args); return result==null?0:result; }
    private Map<String,Object> candidateRow(String id,String cid) {
        uuid(cid); List<Map<String,Object>> found=db.queryForList("select * from candidates where id=? and group_id=?",cid,id);
        if(found.isEmpty()) throw ApiError.missing(); return found.getFirst();
    }
    private Map<String,Object> candidate(Map<String,Object> row) {
        Map<String,Object> candidate=decode(string(row.get("payload")));
        candidate.put("id",row.get("id")); candidate.put("version",row.get("version")); candidate.put("createdBy",row.get("created_by")); return candidate;
    }
    private Map<String,Object> view(String id,String username) {
        Map<String,Object> group=db.queryForMap("select * from activity_groups where id=?",id);
        String role=db.queryForObject("select role from members where group_id=? and username=?",String.class,id,username);
        List<Map<String,Object>> members=db.query("select username,role from members where group_id=? order by username",(r,n)->map("username",r.getString("username"),"role",r.getString("role")),id);
        List<Map<String,Object>> candidates=db.queryForList("select * from candidates where group_id=? order by id",id).stream().map(this::candidate).toList();
        List<Map<String,Object>> preferences=db.query("select p.candidate_id,p.username,p.choice from preferences p join members m on p.group_id=m.group_id and p.username=m.username where p.group_id=? order by p.candidate_id,p.username",(r,n)->map("candidateId",r.getString("candidate_id"),"username",r.getString("username"),"choice",r.getString("choice")),id);
        List<String> decision=db.queryForList("select payload from decisions where group_id=?",String.class,id);
        long latest=latest(id);
        List<Map<String,Object>> pubs=db.query("select id,version,published_at,revoked from publications where group_id=? order by version desc",(r,n)->map("id",r.getString("id"),"version",r.getLong("version"),"publishedAt",r.getString("published_at"),"revoked",r.getBoolean("revoked"),"isLatest",r.getLong("version")==latest),id);
        return map("id",id,"name",group.get("name"),"eventDate",group.get("event_date"),"version",group.get("version"),"role",role,"members",members,"candidates",candidates,"preferences",preferences,"decision",decision.isEmpty()?null:decode(decision.getFirst()),"publications",pubs);
    }
    private Map<String,Object> candidateFields(CandidateInput input) {
        String source=text(input.sourceUrl(),"来源网址",0,2048);
        if(!source.isEmpty()) {
            try {
                URI uri=URI.create(source);
                if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || source.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))) throw ApiError.invalid("来源须为不含账号密码的http/https网址");
            } catch(IllegalArgumentException e) { throw ApiError.invalid("来源网址格式无效"); }
        }
        String verified=date(input.verifiedAt(),"核实日期",false);
        Map<String,Object> fields=map("title",text(input.title(),"候选标题",1,120),"address",text(input.address(),"地点",0,300),"timeWindow",text(input.timeWindow(),"活动时段",0,120),"sourceUrl",source,"verifiedAt",verified);
        String[] names={"opening","fee","reservation"}; String[] texts={input.openingText(),input.feeText(),input.reservationText()},statuses={input.openingStatus(),input.feeStatus(),input.reservationStatus()};
        for(int i=0;i<names.length;i++) {
            String status=text(statuses[i],"规则状态",1,8),value=text(texts[i],"规则说明",0,500);
            if(!Set.of("unknown","verified").contains(status)) throw ApiError.invalid("规则状态仅支持已核实或未核实");
            if(status.equals("verified") && (source.isEmpty()||verified.isEmpty()||value.isEmpty())) throw ApiError.invalid("已核实规则必须填写说明、来源及核实日期");
            fields.put(names[i]+"Text",value); fields.put(names[i]+"Status",status);
        }
        fields.put("internalNote",text(input.internalNote(),"内部讨论",0,2000)); return fields;
    }
    private Map<String,Object> publicCandidate(Map<String,Object> candidate) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String field:List.of("title","address","timeWindow","sourceUrl","verifiedAt","openingText","openingStatus","feeText","feeStatus","reservationText","reservationStatus")) result.put(field,candidate.get(field));
        return result;
    }
    private static long expected(Long version) { if(version==null||version<1) throw ApiError.invalid("请提供有效的expectedVersion"); return version; }
    private static String text(String value,String name,int min,int max) {
        String text=value==null?"":value.strip();
        if(text.length()<min||text.length()>max||text.chars().anyMatch(c->(c<32&&c!='\n'&&c!='\t'&&c!='\r')||c==127)) throw ApiError.invalid(name+"长度须为"+min+"—"+max+"个字符且不含控制字符");
        return text;
    }
    private static String date(String value,String name,boolean required) {
        String v=text(value,name,required?10:0,10); if(v.isEmpty()) return "";
        if(!v.matches("\\d{4}-\\d{2}-\\d{2}")) throw ApiError.invalid(name+"请使用YYYY-MM-DD");
        try { return LocalDate.parse(v).toString(); } catch(DateTimeParseException e) { throw ApiError.invalid(name+"无效"); }
    }
    private static void uuid(String value) { if(value==null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw ApiError.invalid("标识格式无效"); }
    private static String id() { return UUID.randomUUID().toString(); }
    private static String sha256(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String encode(Map<String,Object> value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("Cannot serialize stored data",e); } }
    private Map<String,Object> decode(String value) { try { return json.readValue(value,new TypeReference<LinkedHashMap<String,Object>>(){}); } catch(Exception e) { throw new IllegalStateException("Cannot parse stored data",e); } }
    private static String string(Object value) { if(value instanceof java.sql.Clob clob) try{return clob.getSubString(1,(int)clob.length());}catch(java.sql.SQLException e){throw new IllegalStateException(e);} return (String)value; }
    static Map<String,Object> map(Object... pairs) { Map<String,Object> value=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]); return value; }
}
